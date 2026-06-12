// Copyright (c) 2019 PaddlePaddle Authors. All Rights Reserved.
// Licensed under the Apache License, Version 2.0...

#include "pipeline.h"
#include <iostream>
#include <cstring>
#include <numeric>
#include <vector>
#include <algorithm> // 确保引入算法库用于 sort
#include <android/log.h>

#undef TAG
#define TAG "JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 辅助函数：用于比较两个框的位置，实现阅读顺序排序
// 逻辑：如果 Y 坐标差异小于 10，则按 X 排序；否则按 Y 排序
bool CompareBox(const std::vector<std::vector<int>>& box1, const std::vector<std::vector<int>>& box2) {
    if (box1.empty() || box2.empty()) return false;
    // box[0] 是左上角坐标
    if (abs(box1[0][1] - box2[0][1]) < 10) {
        return box1[0][0] < box2[0][0];
    }
    return box1[0][1] < box2[0][1];
}

cv::Mat GetRotateCropImage(cv::Mat srcimage,
                           std::vector<std::vector<int>> box) {
    cv::Mat image;
    srcimage.copyTo(image);
    std::vector<std::vector<int>> points = box;

    int x_collect[4] = {box[0][0], box[1][0], box[2][0], box[3][0]};
    int y_collect[4] = {box[0][1], box[1][1], box[2][1], box[3][1]};
    int left = int(*std::min_element(x_collect, x_collect + 4));
    int right = int(*std::max_element(x_collect, x_collect + 4));
    int top = int(*std::min_element(y_collect, y_collect + 4));
    int bottom = int(*std::max_element(y_collect, y_collect + 4));

    cv::Mat img_crop;
    image(cv::Rect(left, top, right - left, bottom - top)).copyTo(img_crop);

    for (int i = 0; i < points.size(); i++) {
        points[i][0] -= left;
        points[i][1] -= top;
    }

    int img_crop_width =
            static_cast<int>(sqrt(pow(points[0][0] - points[1][0], 2) +
                                  pow(points[0][1] - points[1][1], 2)));
    int img_crop_height =
            static_cast<int>(sqrt(pow(points[0][0] - points[3][0], 2) +
                                  pow(points[0][1] - points[3][1], 2)));

    cv::Point2f pts_std[4];
    pts_std[0] = cv::Point2f(0., 0.);
    pts_std[1] = cv::Point2f(img_crop_width, 0.);
    pts_std[2] = cv::Point2f(img_crop_width, img_crop_height);
    pts_std[3] = cv::Point2f(0.f, img_crop_height);

    cv::Point2f pointsf[4];
    pointsf[0] = cv::Point2f(points[0][0], points[0][1]);
    pointsf[1] = cv::Point2f(points[1][0], points[1][1]);
    pointsf[2] = cv::Point2f(points[2][0], points[2][1]);
    pointsf[3] = cv::Point2f(points[3][0], points[3][1]);

    cv::Mat M = cv::getPerspectiveTransform(pointsf, pts_std);

    cv::Mat dst_img;
    cv::warpPerspective(img_crop, dst_img, M,
                        cv::Size(img_crop_width, img_crop_height),
                        cv::BORDER_REPLICATE);

    const float ratio = 1.5;
    if (static_cast<float>(dst_img.rows) >=
        static_cast<float>(dst_img.cols) * ratio) {
        cv::Mat srcCopy = cv::Mat(dst_img.rows, dst_img.cols, dst_img.depth());
        cv::transpose(dst_img, srcCopy);
        cv::flip(srcCopy, srcCopy, 0);
        return srcCopy;
    } else {
        return dst_img;
    }
}

std::vector<std::string> ReadDict(std::string path) {
    std::ifstream in(path);
    std::string filename;
    std::string line;
    std::vector<std::string> m_vec;
    if (in) {
        while (getline(in, line)) {
            m_vec.push_back(line);
        }
    } else {
        LOGE("Fail to read dict file: %s", path.c_str());
    }
    return m_vec;
}

std::vector<std::string> split(const std::string &str,
                               const std::string &delim) {
    std::vector<std::string> res;
    if ("" == str)
        return res;
    char *strs = new char[str.length() + 1];
    std::strcpy(strs, str.c_str());

    char *d = new char[delim.length() + 1];
    std::strcpy(d, delim.c_str());

    char *p = std::strtok(strs, d);
    while (p) {
        std::string s = p;
        res.push_back(s);
        p = std::strtok(NULL, d);
    }

    return res;
}

std::map<std::string, double> LoadConfigTxt(std::string config_path) {
    auto config = ReadDict(config_path);

    std::map<std::string, double> dict;
    for (int i = 0; i < config.size(); i++) {
        std::vector<std::string> res = split(config[i], " ");
        dict[res[0]] = stod(res[1]);
    }
    return dict;
}

cv::Mat Visualization(cv::Mat srcimg,
                      std::vector<std::vector<std::vector<int>>> boxes,
                      const std::string &output_image_path) {

    // === 防止验证集崩溃：路径为空则不保存可视化 ===
    if (output_image_path.empty()) {
        return srcimg; // 不做可视化，返回原图即可
    }

    cv::Point rook_points[boxes.size()][4];
    for (int n = 0; n < boxes.size(); n++) {
        for (int m = 0; m < boxes[0].size(); m++) {
            rook_points[n][m] = cv::Point(static_cast<int>(boxes[n][m][0]),
                                          static_cast<int>(boxes[n][m][1]));
        }
    }
    cv::Mat img_vis;
    srcimg.copyTo(img_vis);
    for (int n = 0; n < boxes.size(); n++) {
        const cv::Point *ppt[1] = {rook_points[n]};
        int npt[] = {4};
        cv::polylines(img_vis, ppt, npt, 1, 1, CV_RGB(0, 255, 0), 2, 8, 0);
    }
    cv::Mat img_vis_bgr;
    cv::cvtColor(img_vis, img_vis_bgr, cv::COLOR_RGBA2BGR);

    cv::imwrite(output_image_path, img_vis_bgr);
    return img_vis;
}


void Pipeline::VisualizeResults(std::vector<std::string> rec_text,
                                std::vector<float> rec_text_score,
                                cv::Mat *rgbaImage,
                                double *visualizeResultsTime) {
}

void Pipeline::VisualizeStatus(double readGLFBOTime, double writeGLTextureTime,
                               double predictTime,
                               std::vector<std::string> rec_text,
                               std::vector<float> rec_text_score,
                               double visualizeResultsTime,
                               cv::Mat *rgbaImage) {
}

Pipeline::Pipeline(const std::string &detModelDir,
                   const std::string &clsModelDir,
                   const std::string &recModelDir,
                   const std::string &cPUPowerMode, const int cPUThreadNum,
                   const std::string &config_path,
                   const std::string &dict_path) {
    LOGD("Pipeline::Init Start");
    clsPredictor_.reset(
            new ClsPredictor(clsModelDir, cPUThreadNum, cPUPowerMode));
    detPredictor_.reset(
            new DetPredictor(detModelDir, cPUThreadNum, cPUPowerMode));
    recPredictor_.reset(
            new RecPredictor(recModelDir, cPUThreadNum, cPUPowerMode));
    Config_ = LoadConfigTxt(config_path);
    charactor_dict_ = ReadDict(dict_path);
    charactor_dict_.insert(charactor_dict_.begin(), "#"); // blank char for ctc
    charactor_dict_.push_back(" ");
    LOGD("Pipeline::Init End. Dict size: %d", (int)charactor_dict_.size());
}

bool Pipeline::Process_val(int inTextureId, int outTextureId, int textureWidth,
                           int textureHeight, std::string savedImagePath) {
    return true;
}

std::string Pipeline::Process_Path(std::string inputImagePath, std::string savedImagePath) {
    LOGD("=== Start Process_Path ===");

    // 初始化时间变量
    double t_det = 0.0;
    double t_cls_total = 0.0;
    double t_rec_total = 0.0;

    cv::Mat bgrImage = cv::imread(inputImagePath);
    if (bgrImage.empty()) {
        return "Error: Image not found";
    }

    cv::Mat srcimg;
    bgrImage.copyTo(srcimg);

    // --- Detection ---
    if (Config_.find("max_side_len") == Config_.end()) Config_["max_side_len"] = 960;
    if (Config_.find("det_db_thresh") == Config_.end()) Config_["det_db_thresh"] = 0.3;
    if (Config_.find("det_db_use_dilate") == Config_.end()) Config_["det_db_use_dilate"] = 0;

    // 传入 &t_det 获取检测推理时间
    auto boxes = detPredictor_->Predict(srcimg, Config_, nullptr, nullptr, nullptr, &t_det);

    // 【重要修改】对检测框进行排序，确保阅读顺序（从上到下，从左到右）
    std::sort(boxes.begin(), boxes.end(), CompareBox);

    // --- Recognition Loop ---
    cv::Mat img;
    bgrImage.copyTo(img);
    cv::Mat crop_img;
    std::vector<std::string> rec_text;
    std::vector<float> rec_text_score;

    int use_direction_classify = 1;

    // 【重要修改】改为正序遍历 (i=0 -> size)，因为我们已经排好序了
    for (int i = 0; i < boxes.size(); i++) {
        crop_img = GetRotateCropImage(img, boxes[i]);

        // 如果启用了方向分类
        if (use_direction_classify >= 1) {
            double t_cls_single = 0.0;

            // =========== 修改点 START ===========
            // 之前的代码：clsPredictor_->Predict(crop_img, nullptr, nullptr, nullptr, 0.9);
            // 错误原因：传了 nullptr，无法回传时间。
            // 修正后：将 &t_cls_single 传入第 3 个参数 (predictTime)，记录推理耗时
            crop_img = clsPredictor_->Predict(crop_img, nullptr, &t_cls_single, nullptr, 0.9);
            // =========== 修改点 END =============

            t_cls_total += t_cls_single; // 累加时间
        }

        double t_rec_single = 0.0;
        // Rec 的调用是对的，传入了 &t_rec_single
        auto res = recPredictor_->Predict(crop_img, nullptr, nullptr, nullptr, charactor_dict_, &t_rec_single);
        t_rec_total += t_rec_single; // 累加时间

        rec_text.push_back(res.first);
        rec_text_score.push_back(res.second);
    }


    // --- Visualization ---
    if (!savedImagePath.empty()) {
        Visualization(bgrImage, boxes, savedImagePath);
    }


    // --- 格式化返回结果 ---

    // 1. 拼接时间信息
    char time_buf[256];
    sprintf(time_buf, "Det:%.1fms + Cls:%.1fms + Rec:%.1fms = %.1fms",
            t_det, t_cls_total, t_rec_total, (t_det + t_cls_total + t_rec_total));
    std::string timeInfo(time_buf);

    // 2. 拼接识别到的所有文本
    std::string allTextContent = "";
    for (const auto& text : rec_text) {
        allTextContent += text + "\n"; // 每行一个结果
    }

    // 3. 组合最终结果，使用 #SPLIT# 作为分隔符
    std::string finalResult = timeInfo + "#SPLIT#" + allTextContent;

    LOGD("Return Result: %s", finalResult.c_str());
    return finalResult;
}