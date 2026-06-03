#include <algorithm>
#include <android/bitmap.h>
#include <cmath>
#include <jni.h>

#ifdef __ARM_NEON
#include <arm_neon.h>
#endif

float srgbToLinearLUT[256];
uint8_t linearToSRGBLUT[4096];
bool lutsInitialized = false;

void initVectorizedLUTs() {
  if (lutsInitialized)
    return;

  for (int i = 0; i < 256; i++) {
    float val01 = i / 255.0f;
    srgbToLinearLUT[i] = (val01 <= 0.04045f)
                             ? (val01 / 12.92f)
                             : std::pow((val01 + 0.055f) / 1.055f, 2.4f);
  }

  for (int i = 0; i < 4096; i++) {
    float linearVal = i / 4095.0f;
    float srgb01 = (linearVal <= 0.0031308f)
                       ? (linearVal * 12.92f)
                       : (1.055f * std::pow(linearVal, 1.0f / 2.4f) - 0.055f);
    linearToSRGBLUT[i] =
        (uint8_t)std::max(0.0f, std::min(255.0f, srgb01 * 255.0f));
  }

  lutsInitialized = true;
}

inline uint8_t fastLinearToSRGB(float linearVal) {
  int idx = (int)(linearVal * 4095.0f);
  if (idx < 0)
    idx = 0;
  if (idx > 4095)
    idx = 4095;
  return linearToSRGBLUT[idx];
}

extern "C" JNIEXPORT void JNICALL
Java_moe_grass_webgpuviewer_ImageUtil_resizeLinearAreaNative(
    JNIEnv *env, jobject thiz, jobject src_bitmap, jobject dst_bitmap) {
  initVectorizedLUTs();

  AndroidBitmapInfo srcInfo;
  AndroidBitmapInfo dstInfo;
  void *srcPixels;
  void *dstPixels;

  if (AndroidBitmap_getInfo(env, src_bitmap, &srcInfo) < 0 ||
      AndroidBitmap_getInfo(env, dst_bitmap, &dstInfo) < 0)
    return;
  if (AndroidBitmap_lockPixels(env, src_bitmap, &srcPixels) < 0 ||
      AndroidBitmap_lockPixels(env, dst_bitmap, &dstPixels) < 0)
    return;

  uint32_t *src = (uint32_t *)srcPixels;
  uint32_t *dst = (uint32_t *)dstPixels;

  double scaleX = (double)srcInfo.width / dstInfo.width;
  double scaleY = (double)srcInfo.height / dstInfo.height;

  for (uint32_t y = 0; y < dstInfo.height; ++y) {
    double srcYStart = y * scaleY;
    double srcYEnd = srcYStart + scaleY;
    int yMin = std::max(0, (int)srcYStart);
    int yMax = std::min((int)srcInfo.height - 1, (int)srcYEnd);

    for (uint32_t x = 0; x < dstInfo.width; ++x) {
      double srcXStart = x * scaleX;
      double srcXEnd = srcXStart + scaleX;
      int xMin = std::max(0, (int)srcXStart);
      int xMax = std::min((int)srcInfo.width - 1, (int)srcXEnd);

      uint32_t finalA = 0, finalR = 0, finalG = 0, finalB = 0;

#ifdef __ARM_NEON
      float32x4_t v_sum_a = vdupq_n_f32(0.0f);
      float32x4_t v_sum_r = vdupq_n_f32(0.0f);
      float32x4_t v_sum_g = vdupq_n_f32(0.0f);
      float32x4_t v_sum_b = vdupq_n_f32(0.0f);
      float totalWeight = 0.0f;

      for (int sy = yMin; sy <= yMax; ++sy) {
        double yWeight = std::min((double)sy + 1.0, srcYEnd) -
                         std::max((double)sy, srcYStart);
        if (yWeight <= 0)
          continue;

        int sx = xMin;

        for (; sx <= xMax - 3; sx += 4) {
          float weights[4];
          float linearA[4], linearR[4], linearG[4], linearB[4];

          for (int i = 0; i < 4; ++i) {
            double xWeight = std::min((double)(sx + i) + 1.0, srcXEnd) -
                             std::max((double)(sx + i), srcXStart);
            weights[i] = (xWeight > 0) ? (float)(xWeight * yWeight) : 0.0f;
            totalWeight += weights[i];

            uint32_t pixel = src[sy * srcInfo.width + (sx + i)];
            linearA[i] = ((pixel >> 24) & 0xFF) * 0.00392156862f;
            linearR[i] = srgbToLinearLUT[(pixel >> 16) & 0xFF];
            linearG[i] = srgbToLinearLUT[(pixel >> 8) & 0xFF];
            linearB[i] = srgbToLinearLUT[pixel & 0xFF];
          }

          float32x4_t v_w = vld1q_f32(weights);
          float32x4_t v_a = vld1q_f32(linearA);
          float32x4_t v_r = vld1q_f32(linearR);
          float32x4_t v_g = vld1q_f32(linearG);
          float32x4_t v_b = vld1q_f32(linearB);

          v_sum_a = vmlaq_f32(v_sum_a, v_a, v_w);
          v_sum_r = vmlaq_f32(v_sum_r, v_r, v_w);
          v_sum_g = vmlaq_f32(v_sum_g, v_g, v_w);
          v_sum_b = vmlaq_f32(v_sum_b, v_b, v_w);
        }

        for (; sx <= xMax; ++sx) {
          double xWeight = std::min((double)sx + 1.0, srcXEnd) -
                           std::max((double)sx, srcXStart);
          if (xWeight <= 0)
            continue;

          float pWeight = (float)(xWeight * yWeight);
          totalWeight += pWeight;

          uint32_t pixel = src[sy * srcInfo.width + sx];

          v_sum_a = vsetq_lane_f32(
              vgetq_lane_f32(v_sum_a, 0) +
                  (((pixel >> 24) & 0xFF) * 0.00392156862f) * pWeight,
              v_sum_a, 0);
          v_sum_r = vsetq_lane_f32(vgetq_lane_f32(v_sum_r, 0) +
                                       srgbToLinearLUT[(pixel >> 16) & 0xFF] *
                                           pWeight,
                                   v_sum_r, 0);
          v_sum_g =
              vsetq_lane_f32(vgetq_lane_f32(v_sum_g, 0) +
                                 srgbToLinearLUT[(pixel >> 8) & 0xFF] * pWeight,
                             v_sum_g, 0);
          v_sum_b = vsetq_lane_f32(vgetq_lane_f32(v_sum_b, 0) +
                                       srgbToLinearLUT[pixel & 0xFF] * pWeight,
                                   v_sum_b, 0);
        }
      }

      if (totalWeight > 0.0f) {
        float sumA = vgetq_lane_f32(v_sum_a, 0) + vgetq_lane_f32(v_sum_a, 1) +
                     vgetq_lane_f32(v_sum_a, 2) + vgetq_lane_f32(v_sum_a, 3);
        float sumR = vgetq_lane_f32(v_sum_r, 0) + vgetq_lane_f32(v_sum_r, 1) +
                     vgetq_lane_f32(v_sum_r, 2) + vgetq_lane_f32(v_sum_r, 3);
        float sumG = vgetq_lane_f32(v_sum_g, 0) + vgetq_lane_f32(v_sum_g, 1) +
                     vgetq_lane_f32(v_sum_g, 2) + vgetq_lane_f32(v_sum_g, 3);
        float sumB = vgetq_lane_f32(v_sum_b, 0) + vgetq_lane_f32(v_sum_b, 1) +
                     vgetq_lane_f32(v_sum_b, 2) + vgetq_lane_f32(v_sum_b, 3);

        float invWeight = 1.0f / totalWeight;
        finalA = (uint32_t)std::max(
            0.0f, std::min(255.0f, (sumA * invWeight) * 255.0f));
        finalR = fastLinearToSRGB(sumR * invWeight);
        finalG = fastLinearToSRGB(sumG * invWeight);
        finalB = fastLinearToSRGB(sumB * invWeight);
      }
#else
      float sumA = 0.0f, sumR = 0.0f, sumG = 0.0f, sumB = 0.0f;
      float totalWeight = 0.0f;

      int numXPixels = xMax - xMin + 1;

      float xWeights[256];
      int maxSafeX = (numXPixels > 256) ? 256 : numXPixels;

      for (int i = 0; i < maxSafeX; ++i) {
        int sx = xMin + i;
        double xWeight = std::min((double)sx + 1.0, srcXEnd) -
                         std::max((double)sx, srcXStart);
        xWeights[i] = (xWeight > 0) ? (float)xWeight : 0.0f;
      }

      for (int sy = yMin; sy <= yMax; ++sy) {
        double yWeight = std::min((double)sy + 1.0, srcYEnd) -
                         std::max((double)sy, srcYStart);
        if (yWeight <= 0)
          continue;

        float yWeightF = (float)yWeight;
        int srcRowOffset = sy * srcInfo.width;

        for (int sx = xMin; sx <= xMax; ++sx) {
          int cacheIdx = sx - xMin;
          if (cacheIdx >= 256)
            break;

          float pWeight = xWeights[cacheIdx] * yWeightF;
          if (pWeight <= 0.0f)
            continue;

          uint32_t pixel = src[srcRowOffset + sx];

          uint8_t a = (pixel >> 24) & 0xFF;
          uint8_t r = (pixel >> 16) & 0xFF;
          uint8_t g = (pixel >> 8) & 0xFF;
          uint8_t b = pixel & 0xFF;

          sumA += (a * 0.00392156862f) * pWeight;
          sumR += srgbToLinearLUT[r] * pWeight;
          sumG += srgbToLinearLUT[g] * pWeight;
          sumB += srgbToLinearLUT[b] * pWeight;

          totalWeight += pWeight;
        }
      }

      if (totalWeight > 0.0f) {
        float invWeight = 1.0f / totalWeight;

        finalA = (uint32_t)std::max(
            0.0f, std::min(255.0f, (sumA * invWeight) * 255.0f));
        finalR = fastLinearToSRGB(sumR * invWeight);
        finalG = fastLinearToSRGB(sumG * invWeight);
        finalB = fastLinearToSRGB(sumB * invWeight);
      }
#endif

      dst[y * dstInfo.width + x] =
          (finalA << 24) | (finalR << 16) | (finalG << 8) | finalB;
    }
  }

  AndroidBitmap_unlockPixels(env, src_bitmap);
  AndroidBitmap_unlockPixels(env, dst_bitmap);
}
