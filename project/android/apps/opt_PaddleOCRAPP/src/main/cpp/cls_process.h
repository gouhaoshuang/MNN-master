#pragma once
#include "opencv2/core.hpp"
#include "opencv2/imgcodecs.hpp"
#include "opencv2/imgproc.hpp"
#include "utils.h"

// MNN Includes
#include "MNN/Interpreter.hpp"
#include "MNN/ImageProcess.hpp"
#include "MNN/Tensor.hpp"

class ClsPredictor {
public:
    explicit ClsPredictor(const std::string &modelDir, const int cpuThreadNum,
                          const std::string &cpuPowerMode);
    ~ClsPredictor();

    cv::Mat Predict(const cv::Mat &rgbImage, double *preprocessTime,
                    double *predictTime, double *postprocessTime,
                    const float thresh);



private:
    void Preprocess(const cv::Mat &rgbaImage);
    cv::Mat Postprocess(const cv::Mat &img, const float thresh);

private:
    std::shared_ptr<MNN::Interpreter> interpreter_;
    MNN::Session* session_ = nullptr;
    MNN::Tensor* input_tensor_ = nullptr;
};