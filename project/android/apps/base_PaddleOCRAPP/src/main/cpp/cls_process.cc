#include "cls_process.h"
#include <iostream>
#include <algorithm>
#include <memory>
#include <cmath>

// OCR v4 CLS 模型标准输入尺寸
const std::vector<int> cls_image_shape{3, 48, 192};

cv::Mat ClsResizeImg(cv::Mat img) {
    int imgC = cls_image_shape[0];
    int imgH = cls_image_shape[1];
    int imgW = cls_image_shape[2];

    // 计算缩放比例
    float ratio = static_cast<float>(img.cols) / static_cast<float>(img.rows);
    int resize_w = static_cast<int>(ceilf(imgH * ratio));

    // 限制最大宽度，如果超过 192 则截断（OCR v4 逻辑）
    if (resize_w > imgW) {
        resize_w = imgW;
    }

    cv::Mat resize_img;
    cv::resize(img, resize_img, cv::Size(resize_w, imgH), 0.f, 0.f, cv::INTER_LINEAR);

    // 如果宽度不足 192，在右侧进行 Padding (填充黑色/0)
    // 这是 PaddleOCR CLS 模型标准处理方式，保证输入 Tensor 始终为 48x192
    if (resize_w < imgW) {
        cv::copyMakeBorder(resize_img, resize_img, 0, 0, 0, imgW - resize_w,
                           cv::BORDER_CONSTANT, cv::Scalar(0, 0, 0));
    }
    return resize_img;
}

ClsPredictor::ClsPredictor(const std::string &modelDir, const int cpuThreadNum,
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

    // 预先设置好固定尺寸，CLS 模型输入通常固定为 1, 3, 48, 192
    // 避免在 Predict 中重复 resize session
    interpreter_->resizeTensor(input_tensor_, {1, 3, cls_image_shape[1], cls_image_shape[2]});
    interpreter_->resizeSession(session_);
}

ClsPredictor::~ClsPredictor() {
    // smart pointer will handle release
}

void ClsPredictor::Preprocess(const cv::Mat &img) {
    // 1. Resize & Pad
    // 注意：这里不需要深拷贝 copyTo，因为 resize 会产生新 Mat
    cv::Mat resize_img = ClsResizeImg(img);

    // 2. 归一化配置
    // PaddleOCR CLS v4 使用 MobileNetV3
    // Mean: [0.5, 0.5, 0.5], Std: [0.5, 0.5, 0.5], Scale: 1/255
    // 转换公式: (x/255 - 0.5) / 0.5 = (x - 127.5) / 127.5
    // 所以 mean = 127.5, normal = 1/127.5 (~0.007843)

    MNN::CV::ImageProcess::Config processConfig;
    processConfig.filterType = MNN::CV::BILINEAR;

    // [CRITICAL FIX] 关键修正：
    // OpenCV 读取的是 BGR，Paddle 模型训练用的是 RGB。
    // 这里必须指定 destFormat 为 RGB，否则预测结果会错乱。
    processConfig.sourceFormat = MNN::CV::BGR;
    processConfig.destFormat = MNN::CV::RGB;

    processConfig.wrap = MNN::CV::ZERO;

    const float mean[3] = {127.5f, 127.5f, 127.5f};
    const float normal[3] = {0.007843137f, 0.007843137f, 0.007843137f};

    ::memcpy(processConfig.mean, mean, sizeof(mean));
    ::memcpy(processConfig.normal, normal, sizeof(normal));

    std::shared_ptr<MNN::CV::ImageProcess> pretreat(MNN::CV::ImageProcess::create(processConfig));

    // 3. 转换并拷贝到 Tensor
    // resize_img 已经是 48x192，直接转换
    pretreat->convert((uint8_t*)resize_img.data, resize_img.cols, resize_img.rows, (int)resize_img.step[0], input_tensor_);
}

cv::Mat ClsPredictor::Postprocess(const cv::Mat &srcimg, const float thresh) {
    // 获取输出
    MNN::Tensor* outputTensor = interpreter_->getSessionOutput(session_, nullptr);

    // Copy to host
    std::shared_ptr<MNN::Tensor> hostOutput(new MNN::Tensor(outputTensor, outputTensor->getDimensionType()));
    outputTensor->copyToHostTensor(hostOutput.get());

    auto *softmax_scores = hostOutput->host<float>();
    auto shape = hostOutput->shape(); // 通常是 [1, 2]

    // Paddle CLS 输出通常已经是 Softmax 后的概率值
    // Index 0: 0度 (正向)
    // Index 1: 180度 (倒向)

    int label_idx = 0;
    float score = 0.0f;
    int cls_num = 1;
    if (shape.size() >= 2) cls_num = shape[1];

    // 寻找最大概率的标签
    for (int i = 0; i < cls_num; ++i) {
        if (softmax_scores[i] > score) {
            score = softmax_scores[i];
            label_idx = i;
        }
    }

    // 官方逻辑：如果标签是 1 (180度) 且置信度 > 阈值，则旋转
    // cv::rotate 第二个参数：0=90顺时针, 1=180, 2=90逆时针
    // 这里传入 1 代表旋转 180 度，符合逻辑
    if (label_idx == 1 && score > thresh) {
        cv::Mat rotated;
        cv::rotate(srcimg, rotated, 1);
        return rotated;
    }

    return srcimg;
}

cv::Mat ClsPredictor::Predict(const cv::Mat &img, double *preprocessTime,
                              double *predictTime, double *postprocessTime,
                              const float thresh) {
    // 计时并执行 Preprocess
    auto t0 = GetCurrentTime();
    Preprocess(img);
    if (preprocessTime) *preprocessTime = GetElapsedTime(t0);

    // Run
    auto t1 = GetCurrentTime();
    interpreter_->runSession(session_);
    if (predictTime) *predictTime = GetElapsedTime(t1);

    // Postprocess
    auto t2 = GetCurrentTime();
    cv::Mat result_img = Postprocess(img, thresh);
    if (postprocessTime) *postprocessTime = GetElapsedTime(t2);

    return result_img;
}
