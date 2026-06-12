#pragma once

#include "opencv2/core.hpp"
#include "opencv2/imgcodecs.hpp"
#include "opencv2/imgproc.hpp"
#include "utils.h"

// 引入 MNN 头文件
#include "MNN/Interpreter.hpp"
#include "MNN/ImageProcess.hpp"
#include "MNN/Tensor.hpp"

class DetPredictor {
public:
    explicit DetPredictor(const std::string &modelDir, const int cpuThreadNum,
                          const std::string &cpuPowerMode);

    // 析构函数中释放 Session
    ~DetPredictor();

    std::vector<std::vector<std::vector<int>>>
    Predict(cv::Mat &rgbImage, std::map<std::string, double> Config,
            double *preprocessTime, double *predictTime, double *postprocessTime,double *inferenceTime);

private:
    void Preprocess(const cv::Mat &img, const int max_side_len);

    std::vector<std::vector<std::vector<int>>>
    Postprocess(const cv::Mat srcimg, std::map<std::string, double> Config,
                int det_db_use_dilate);

private:
    std::vector<float> ratio_hw_;

    // MNN 核心组件
    std::shared_ptr<MNN::Interpreter> interpreter_;
    MNN::Session* session_ = nullptr;
    MNN::Tensor* input_tensor_ = nullptr;
};