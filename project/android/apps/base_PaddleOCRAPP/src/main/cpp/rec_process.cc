#include "rec_process.h"
#include "utils.h"
#include <iostream>
#include <cmath>
#include <numeric>

// PaddleOCR 默认训练高度
const int REC_IMG_H = 48;

// ------------------- 分桶 Resize -------------------
cv::Mat CrnnResizeImg(cv::Mat img, float wh_ratio) {
    int imgH = REC_IMG_H;
    const int MAX_W = 320;  // 对齐官方 rec_img_width

    float ratio = static_cast<float>(img.cols) / static_cast<float>(img.rows);
    int real_resize_w = static_cast<int>(ceilf(imgH * ratio));

    // 限制宽度在 [16, 320]
    if (real_resize_w > MAX_W) real_resize_w = MAX_W;
    if (real_resize_w < 16)    real_resize_w = 16;

    cv::Mat resize_img;
    cv::resize(img, resize_img, cv::Size(real_resize_w, imgH), 0.f, 0.f, cv::INTER_LINEAR);

    // 不足 320 的右侧 padding 到 320，保持和官方一样的方式
    if (real_resize_w < MAX_W) {
        cv::Mat padded_img;
        cv::copyMakeBorder(resize_img, padded_img, 0, 0, 0, MAX_W - real_resize_w,
                           cv::BORDER_CONSTANT, cv::Scalar(127, 127, 127));
        return padded_img;
    }
    return resize_img;
}

template <class ForwardIterator>
inline size_t Argmax(ForwardIterator first, ForwardIterator last) {
    return std::distance(first, std::max_element(first, last));
}

// ------------------- RecPredictor -------------------
RecPredictor::RecPredictor(const std::string &modelDir, const int cpuThreadNum,
                           const std::string &cpuPowerMode) {
    interpreter_ = std::shared_ptr<MNN::Interpreter>(MNN::Interpreter::createFromFile(modelDir.c_str()));

    MNN::ScheduleConfig config;
    config.numThread = cpuThreadNum;
    config.type = MNN_FORWARD_CPU;

    MNN::BackendConfig backendConfig;
    backendConfig.precision = MNN::BackendConfig::Precision_High;
    backendConfig.power = MNN::BackendConfig::Power_High;
    config.backendConfig = &backendConfig;

    session_ = interpreter_->createSession(config);
    input_tensor_ = interpreter_->getSessionInput(session_, nullptr);

    MNN::CV::ImageProcess::Config processConfig;
    processConfig.filterType = MNN::CV::BILINEAR;
    processConfig.sourceFormat = MNN::CV::BGR;
    processConfig.destFormat = MNN::CV::RGB;
    processConfig.wrap = MNN::CV::ZERO;

    const float mean[3] = {127.5f, 127.5f, 127.5f};
    const float normal[3] = {0.007843137f, 0.007843137f, 0.007843137f};
    ::memcpy(processConfig.mean, mean, sizeof(mean));
    ::memcpy(processConfig.normal, normal, sizeof(normal));

    pretreat_ = std::shared_ptr<MNN::CV::ImageProcess>(MNN::CV::ImageProcess::create(processConfig));
}

void RecPredictor::Preprocess(const cv::Mat &srcimg) {
    float wh_ratio = static_cast<float>(srcimg.cols) / static_cast<float>(srcimg.rows);
    cv::Mat resize_img = CrnnResizeImg(srcimg, wh_ratio);

    auto current_shape = input_tensor_->shape();
    bool shape_changed = false;
    if (current_shape.size() >= 4) {
        if (current_shape[2] != resize_img.rows || current_shape[3] != resize_img.cols)
            shape_changed = true;
    } else shape_changed = true;

    if (shape_changed) {
        interpreter_->resizeTensor(input_tensor_, {1, 3, resize_img.rows, resize_img.cols});
        interpreter_->resizeSession(session_);
    }

    pretreat_->convert(resize_img.data, resize_img.cols, resize_img.rows, 0, input_tensor_);
}

std::pair<std::string, float> RecPredictor::Postprocess(
        const cv::Mat &rgbaImage,
        std::vector<std::string> charactor_dict) {
    MNN::Tensor* outputTensor = interpreter_->getSessionOutput(session_, nullptr);
    std::shared_ptr<MNN::Tensor> hostOutput(new MNN::Tensor(outputTensor, outputTensor->getDimensionType()));
    outputTensor->copyToHostTensor(hostOutput.get());

    auto predict_batch = hostOutput->host<float>();
    auto predict_shape = hostOutput->shape();
    std::string str_res;
    int last_index = 0;
    float score = 0.f;
    int count = 0;

    int seq_len = predict_shape[1];
    int num_classes = predict_shape[2];

    for (int n = 0; n < seq_len; n++) {
        int start_idx = n * num_classes;
        int end_idx = (n + 1) * num_classes;
        int argmax_idx = int(Argmax(&predict_batch[start_idx], &predict_batch[end_idx]));
        float max_value = *std::max_element(&predict_batch[start_idx], &predict_batch[end_idx]);

        if (argmax_idx > 0 && (!(n > 0 && argmax_idx == last_index))) {
            score += max_value;
            count++;
            if (argmax_idx < charactor_dict.size())
                str_res += charactor_dict[argmax_idx];
        }
        last_index = argmax_idx;
    }
    if (count > 0) score /= count;

    return {str_res, score};
}

std::pair<std::string, float> RecPredictor::Predict(
        const cv::Mat &rgbaImage,
        double *preprocessTime,
        double *predictTime,
        double *postprocessTime,
        std::vector<std::string> charactor_dict,
        double *inferenceTime) {

    Preprocess(rgbaImage);

    auto t_infer = GetCurrentTime();
    interpreter_->runSession(session_);
    if (inferenceTime) *inferenceTime = GetElapsedTime(t_infer);

    return Postprocess(rgbaImage, charactor_dict);
}
