#pragma once

#include "opencv2/core.hpp"
#include "opencv2/imgcodecs.hpp"
#include "opencv2/imgproc.hpp"
#include "utils.h"
#include <memory>
#include <string>
#include <vector>
#include "MNN/Interpreter.hpp"
#include "MNN/ImageProcess.hpp"
#include "MNN/Tensor.hpp"

class RecPredictor {
public:
    explicit RecPredictor(const std::string &modelDir, const int cpuThreadNum,
                          const std::string &cpuPowerMode);

    ~RecPredictor() {
        if (session_ != nullptr) {
            interpreter_->releaseSession(session_);
            session_ = nullptr;
        }
        input_tensor_ = nullptr;
        pretreat_.reset();
    }

    std::pair<std::string, float>
    Predict(const cv::Mat &rgbaImage, double *preprocessTime, double *predictTime,
            double *postprocessTime, std::vector<std::string> charactor_dict, double *inferenceTime = nullptr);

private:
    void Preprocess(const cv::Mat &srcimg);
    std::pair<std::string, float>
    Postprocess(const cv::Mat &srcimg, std::vector<std::string> charactor_dict);

private:
    std::shared_ptr<MNN::Interpreter> interpreter_;
    MNN::Session* session_ = nullptr;
    MNN::Tensor* input_tensor_ = nullptr;
    std::shared_ptr<MNN::CV::ImageProcess> pretreat_;
};
