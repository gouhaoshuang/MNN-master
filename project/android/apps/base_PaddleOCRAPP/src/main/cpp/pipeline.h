// Copyright (c) 2020 PaddlePaddle Authors. All Rights Reserved.
// Licensed under the Apache License, Version 2.0...

#pragma once

// 1. 引入必要的头文件
#include "cls_process.h"
#include "det_process.h"
#include "rec_process.h"
// 必须引入 utils.h，因为下面的 CreateRGBAImageFromGLFBOTexture 用到了 GetCurrentTime
#include "utils.h"

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <opencv2/core.hpp>
#include <opencv2/highgui/highgui.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <string>
#include <vector>

// 2. 删除 paddle 命名空间引用
// using namespace paddle::lite_api; // NOLINT <--- 已删除

class Pipeline {
public:
    std::string Process_Path(std::string inputImagePath, std::string savedImagePath);
    Pipeline(const std::string &detModelDir, const std::string &clsModelDir,
             const std::string &recModelDir, const std::string &cPUPowerMode,
             const int cPUThreadNum, const std::string &config_path,
             const std::string &dict_path);

    bool Process_val(int inTextureId, int outTextureId, int textureWidth,
                     int textureHeight, std::string savedImagePath);

private:
    // Read pixels from FBO texture to CV image
    void CreateRGBAImageFromGLFBOTexture(int textureWidth, int textureHeight,
                                         cv::Mat *rgbaImage,
                                         double *readGLFBOTime) {
        // GetCurrentTime 来自 utils.h
        auto t = GetCurrentTime();
        rgbaImage->create(textureHeight, textureWidth, CV_8UC4);
        glReadPixels(0, 0, textureWidth, textureHeight, GL_RGBA, GL_UNSIGNED_BYTE,
                     rgbaImage->data);
        *readGLFBOTime = GetElapsedTime(t);
        LOGD("Read from FBO texture costs %f ms", *readGLFBOTime);
    }
    // Write back to texture2D
    void WriteRGBAImageBackToGLTexture(const cv::Mat &rgbaImage, int textureId,
                                       double *writeGLTextureTime) {
        auto t = GetCurrentTime();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, rgbaImage.cols, rgbaImage.rows,
                        GL_RGBA, GL_UNSIGNED_BYTE, rgbaImage.data);
        *writeGLTextureTime = GetElapsedTime(t);
        LOGD("Write back to texture2D costs %f ms", *writeGLTextureTime);
    }
    // Visualize the results to image
    void VisualizeResults(std::vector<std::string> rec_text,
                          std::vector<float> rec_text_score, cv::Mat *rgbaImage,
                          double *visualizeResultsTime);
    // Visualize the status(performace data) to image
    void VisualizeStatus(double readGLFBOTime, double writeGLTextureTime,
                         double predictTime, std::vector<std::string> rec_text,
                         std::vector<float> rec_text_score,
                         double visualizeResultsTime, cv::Mat *rgbaImage);

private:
    std::map<std::string, double> Config_;
    std::vector<std::string> charactor_dict_;
    std::shared_ptr<ClsPredictor> clsPredictor_;
    std::shared_ptr<DetPredictor> detPredictor_;
    std::shared_ptr<RecPredictor> recPredictor_;
};