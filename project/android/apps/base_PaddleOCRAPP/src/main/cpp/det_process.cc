#include "det_process.h"
#include "db_post_process.h"
#include <memory>

// 保持原有的 Resize 逻辑不变，因为这是 DBNet 算法要求的
cv::Mat DetResizeImg(const cv::Mat img, int max_size_len,
                     std::vector<float> &ratio_hw) {
    int w = img.cols;
    int h = img.rows;
    float ratio = 1.f;
    int max_wh = w >= h ? w : h;
    if (max_wh > max_size_len) {
        if (h > w) {
            ratio = static_cast<float>(max_size_len) / static_cast<float>(h);
        } else {
            ratio = static_cast<float>(max_size_len) / static_cast<float>(w);
        }
    }

    int resize_h = static_cast<int>(float(h) * ratio);
    int resize_w = static_cast<int>(float(w) * ratio);

    // 确保是 32 的倍数
    if (resize_h % 32 == 0)
        resize_h = resize_h;
    else if (resize_h / 32 < 1 + 1e-5)
        resize_h = 32;
    else
        resize_h = (resize_h / 32 - 1) * 32;

    if (resize_w % 32 == 0)
        resize_w = resize_w;
    else if (resize_w / 32 < 1 + 1e-5)
        resize_w = 32;
    else
        resize_w = (resize_w / 32 - 1) * 32;

    cv::Mat resize_img;
    cv::resize(img, resize_img, cv::Size(resize_w, resize_h));

    ratio_hw.push_back(static_cast<float>(resize_h) / static_cast<float>(h));
    ratio_hw.push_back(static_cast<float>(resize_w) / static_cast<float>(w));
    return resize_img;
}

DetPredictor::DetPredictor(const std::string &modelDir, const int cpuThreadNum,
                           const std::string &cpuPowerMode) {
    // 1. 加载模型
    interpreter_ = std::shared_ptr<MNN::Interpreter>(
            MNN::Interpreter::createFromFile(modelDir.c_str()));

    // 2. 配置 Session
    MNN::ScheduleConfig config;
    config.numThread = cpuThreadNum;         // 确保用你传进来的线程数（1）
    config.type      = MNN_FORWARD_CPU;      // 强制走 CPU 后端

    MNN::BackendConfig backendConfig;
    backendConfig.precision = MNN::BackendConfig::Precision_High;  // 必须为Precision_High
    // backendConfig.precision = MNN::BackendConfig::Precision_Low;

    backendConfig.power     = MNN::BackendConfig::Power_High;
    config.backendConfig    = &backendConfig;




    // 3. 创建 Session
    session_      = interpreter_->createSession(config);
    input_tensor_ = interpreter_->getSessionInput(session_, nullptr);
}

DetPredictor::~DetPredictor() {
    // interpreter_ 是智能指针，会自动释放
    // session_ 由 interpreter 管理，interpreter 释放时会自动释放 session
    // input_tensor_ 属于 session，无需手动 delete
}

void DetPredictor::Preprocess(const cv::Mat &srcimg, const int max_side_len) {
    // 1. DB 算法特定的 Resize
    cv::Mat img = DetResizeImg(srcimg, max_side_len, ratio_hw_);

    // 2. 调整 MNN Tensor 尺寸以匹配输入图片 (NCHW)
    // PaddleOCR 导出的模型输入通常是 1, 3, H, W
    interpreter_->resizeTensor(input_tensor_, {1, 3, img.rows, img.cols});
    interpreter_->resizeSession(session_);

    // 3. 设置 MNN ImageProcess 进行归一化和数据填充
    // MNN 的处理流程：(pixel - mean) * normal
    // Paddle 的参数：mean={0.485, 0.456, 0.406}, scale=1/0.229... (基于 0-1 float)
    // 转换到 MNN (基于 0-255 uint8):
    // Mean = PaddleMean * 255
    // Normal = 1 / (PaddleStd * 255)

    MNN::CV::ImageProcess::Config processConfig;
    processConfig.filterType = MNN::CV::BILINEAR;
    processConfig.sourceFormat = MNN::CV::BGR; // OpenCV 默认是 BGR
    processConfig.destFormat = MNN::CV::RGB;   // 模型通常需要 RGB
    processConfig.wrap = MNN::CV::ZERO;

    // 预计算好的 PaddleOCR 标准化参数
    const float mean[3] = {127.5f, 127.5f, 127.5f};
    const float normal[3] = {0.007843137f, 0.007843137f, 0.007843137f};

    ::memcpy(processConfig.mean, mean, sizeof(mean));
    ::memcpy(processConfig.normal, normal, sizeof(normal));

    std::shared_ptr<MNN::CV::ImageProcess> pretreat(MNN::CV::ImageProcess::create(processConfig));

    // 执行转换：BGR(Mat) -> Float32 -> Normalize -> NCHW Tensor
    pretreat->convert(img.data, img.cols, img.rows, 0, input_tensor_);
}

std::vector<std::vector<std::vector<int>>>
DetPredictor::Postprocess(const cv::Mat srcimg,
                          std::map<std::string, double> Config,
                          int det_db_use_dilate) {
    // 1. 获取输出 Tensor
    MNN::Tensor* outputTensor = interpreter_->getSessionOutput(session_, nullptr);

    // 2. 将数据拷贝到 Host (确保数据在 CPU 且是线性排列)
    // 创建一个临时的 host tensor 来承接数据
    std::shared_ptr<MNN::Tensor> hostOutput(new MNN::Tensor(outputTensor, outputTensor->getDimensionType()));
    outputTensor->copyToHostTensor(hostOutput.get());

    auto shape_out = hostOutput->shape(); // NCHW: [1, 1, H, W]
    auto *outptr = hostOutput->host<float>();

    // 下面的逻辑与原 Paddle 代码保持完全一致
    // Save output
    int out_size = shape_out[2] * shape_out[3];
    std::vector<float> pred(out_size);
    std::vector<unsigned char> cbuf(out_size);

    for (int i = 0; i < out_size; i++) {
        pred[i] = static_cast<float>(outptr[i]);
        cbuf[i] = static_cast<unsigned char>(outptr[i] * 255);
    }
    cv::Mat cbuf_map(shape_out[2], shape_out[3], CV_8UC1, cbuf.data());
    cv::Mat pred_map(shape_out[2], shape_out[3], CV_32F, pred.data());

    const double threshold = double(Config["det_db_thresh"]) * 255;
    const double max_value = 255;
    cv::Mat bit_map;
    cv::threshold(cbuf_map, bit_map, threshold, max_value, cv::THRESH_BINARY);

    if (det_db_use_dilate == 1) {
        cv::Mat dilation_map;
        cv::Mat dila_ele =
                cv::getStructuringElement(cv::MORPH_RECT, cv::Size(2, 2));
        cv::dilate(bit_map, dilation_map, dila_ele);
        bit_map = dilation_map;
    }

    // BoxesFromBitmap 通常在 db_post_process.h/.cc 中
    auto boxes = BoxesFromBitmap(pred_map, bit_map, Config);

    std::vector<std::vector<std::vector<int>>> filter_boxes =
                                                       FilterTagDetRes(boxes, ratio_hw_[0], ratio_hw_[1], srcimg);

    return filter_boxes;
}

// 修改 Predict 实现
std::vector<std::vector<std::vector<int>>>
DetPredictor::Predict(cv::Mat &img, std::map<std::string, double> Config,
                      double *preprocessTime, double *predictTime,
                      double *postprocessTime, double *inferenceTime) { // <--- 加参数
    cv::Mat srcimg;
    img.copyTo(srcimg);
    ratio_hw_.clear();
    int max_side_len = int(Config["max_side_len"]);
    int det_db_use_dilate = int(Config["det_db_use_dilate"]);

    // 1. 预处理
    Preprocess(img, max_side_len);

    // 2. 推理 (只统计这一行的时间)
    auto t_infer = GetCurrentTime();
    interpreter_->runSession(session_);
    if (inferenceTime != nullptr) {
        *inferenceTime = GetElapsedTime(t_infer);
    }

    // 3. 后处理
    auto filter_boxes = Postprocess(srcimg, Config, det_db_use_dilate);
    return filter_boxes;
}