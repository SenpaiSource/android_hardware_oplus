/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "Vibrator.h"

#include <cutils/properties.h>
#include <inttypes.h>
#include <log/log.h>

#include <thread>

#include "aac_vibra_function.h"

#define RICHTAP_LIGHT_STRENGTH 69
#define RICHTAP_MEDIUM_STRENGTH 89
#define RICHTAP_STRONG_STRENGTH 99

namespace aidl {
namespace android {
namespace hardware {
namespace vibrator {

Vibrator::Vibrator() {
    uint32_t deviceType = 0;

    int32_t ret = aac_vibra_init(&deviceType);
    if (ret) {
        ALOGE("AAC init failed: %d\n", ret);
        return;
    }

    aac_vibra_looper_start();

    ALOGI("AAC init success: %u\n", deviceType);
}

static uint32_t mapEffectToAacId(Effect effect, int32_t* outStrengthOverride) {
    int32_t id = static_cast<int32_t>(effect);

    // Direct AAC prebaked effect ID (0x1000..0x1002) or JND effect ID (0x3001..0x300b) pass-through
    if ((id >= 0x1000 && id <= 0x1002) || (id >= 0x3001 && id <= 0x300b)) {
        return static_cast<uint32_t>(id);
    }

    switch (effect) {
        case Effect::CLICK:
            return 0x1002;
        case Effect::DOUBLE_CLICK:
            if (outStrengthOverride) *outStrengthOverride = RICHTAP_STRONG_STRENGTH;
            return 0x1001;
        case Effect::TICK:
            return 0x1000;
        case Effect::THUD:
            if (outStrengthOverride) *outStrengthOverride = RICHTAP_STRONG_STRENGTH;
            return 0x1001;
        case Effect::POP:
            return 0x1002;
        case Effect::HEAVY_CLICK:
            if (outStrengthOverride) *outStrengthOverride = RICHTAP_STRONG_STRENGTH;
            return 0x1001;
        case Effect::TEXTURE_TICK:
            if (outStrengthOverride) *outStrengthOverride = RICHTAP_LIGHT_STRENGTH;
            return 0x1000;
        default:
            break;
    }

    // RichTap / OOS vendor effects passed down through prebaked IDs
    switch (id) {
        case 68: // EFFECT_OOS_GRANULAR_WEAK
            if (outStrengthOverride) *outStrengthOverride = RICHTAP_LIGHT_STRENGTH;
            return 0x1000;
        case 69: // EFFECT_OOS_GRANULAR_STRONG (KEYBOARD_TAP / SIGNATURE DETENT)
            if (outStrengthOverride) *outStrengthOverride = RICHTAP_STRONG_STRENGTH;
            return 0x1002;
        case 2:  // EFFECT_OOS_MODERATE_SHORT (CONFIRM / LONG_PRESS)
            if (outStrengthOverride) *outStrengthOverride = RICHTAP_MEDIUM_STRENGTH;
            return 0x1001;
        case 3:  // EFFECT_OOS_MODERATE_SHORT_TWICE
            if (outStrengthOverride) *outStrengthOverride = RICHTAP_STRONG_STRENGTH;
            return 0x1001;
        case 9:
            return 0x1002;
        case 10: // EFFECT_OOS_MICRO_TICK (KEYBOARD_RELEASE / SCROLL_TICK)
        case 11:
        case 12:
            if (outStrengthOverride) *outStrengthOverride = RICHTAP_LIGHT_STRENGTH;
            return 0x1000;
        case 156: // EFFECT_OOS_FINGERPRINT_CORRECT
            if (outStrengthOverride) *outStrengthOverride = RICHTAP_STRONG_STRENGTH;
            return 0x1001;
        case 157: // KEYBOARD_WEAK
            if (outStrengthOverride) *outStrengthOverride = RICHTAP_LIGHT_STRENGTH;
            return 0x1000;
        case 158: // KEYBOARD_MEDIUM
            if (outStrengthOverride) *outStrengthOverride = RICHTAP_MEDIUM_STRENGTH;
            return 0x1002;
        case 159: // KEYBOARD_STRONG
            if (outStrengthOverride) *outStrengthOverride = RICHTAP_STRONG_STRENGTH;
            return 0x1002;
        case 308: // ALERTSLIDER_DOWN
        case 430: // THREE_STAGE_KEY
            if (outStrengthOverride) *outStrengthOverride = RICHTAP_STRONG_STRENGTH;
            return 0x1001;
        case 368: // EFFECT_OOS_VIRTUAL_KEY
            if (outStrengthOverride) *outStrengthOverride = RICHTAP_STRONG_STRENGTH;
            return 0x1002;
        case 409: // SCREENSHOT
            if (outStrengthOverride) *outStrengthOverride = RICHTAP_STRONG_STRENGTH;
            return 0x1001;
        default:
            return 0x1002;
    }
}

ndk::ScopedAStatus Vibrator::getCapabilities(int32_t* _aidl_return) {
    *_aidl_return = IVibrator::CAP_ON_CALLBACK | IVibrator::CAP_PERFORM_CALLBACK |
                    IVibrator::CAP_AMPLITUDE_CONTROL | IVibrator::CAP_COMPOSE_EFFECTS;

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::off() {
    int32_t ret = aac_vibra_off();
    if (ret) {
        ALOGE("AAC off failed: %d\n", ret);
        return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_SERVICE_SPECIFIC));
    }

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::on(int32_t timeoutMs,
                                const std::shared_ptr<IVibratorCallback>& callback) {
    int32_t ret = aac_vibra_looper_on(timeoutMs);
    if (ret < 0) {
        ALOGE("AAC on failed: %d\n", ret);
        return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_SERVICE_SPECIFIC));
    }

    if (callback != nullptr) {
        std::thread([=] {
            usleep(ret * 1000);
            callback->onComplete();
        }).detach();
    }

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::perform(Effect effect, EffectStrength es,
                                     const std::shared_ptr<IVibratorCallback>& callback,
                                     int32_t* _aidl_return) {
    int32_t strength;

    switch (es) {
        case EffectStrength::LIGHT:
            strength = RICHTAP_LIGHT_STRENGTH;
            break;
        case EffectStrength::MEDIUM:
            strength = RICHTAP_MEDIUM_STRENGTH;
            break;
        case EffectStrength::STRONG:
            strength = RICHTAP_STRONG_STRENGTH;
            break;
        default:
            strength = RICHTAP_MEDIUM_STRENGTH;
            break;
    }

    int32_t strengthOverride = -1;
    uint32_t aac_effect = mapEffectToAacId(effect, &strengthOverride);
    if (strengthOverride >= 0) {
        strength = strengthOverride;
    }

    int32_t ret = aac_vibra_looper_prebaked_effect(aac_effect, strength);
    if (ret < 0) {
        ALOGW("AAC perform failed for effect %d (aac 0x%x): %d, fallback to looper on\n",
              static_cast<int32_t>(effect), aac_effect, ret);
        ret = aac_vibra_looper_on(25);
    }

    if (ret <= 0) {
        ret = 25;
    }

    if (callback != nullptr) {
        std::thread([=] {
            usleep(ret * 1000);
            callback->onComplete();
        }).detach();
    }

    *_aidl_return = ret;

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getSupportedEffects(std::vector<Effect>* _aidl_return) {
    *_aidl_return = {
        Effect::CLICK, Effect::DOUBLE_CLICK, Effect::TICK,
        Effect::THUD,  Effect::POP,          Effect::HEAVY_CLICK,
        Effect::TEXTURE_TICK,
        static_cast<Effect>(2),   // EFFECT_OOS_MODERATE_SHORT
        static_cast<Effect>(3),   // EFFECT_OOS_MODERATE_SHORT_TWICE
        static_cast<Effect>(10),  // EFFECT_OOS_MICRO_TICK
        static_cast<Effect>(68),  // EFFECT_OOS_GRANULAR_WEAK
        static_cast<Effect>(69),  // EFFECT_OOS_GRANULAR_STRONG
        static_cast<Effect>(156), // EFFECT_OOS_FINGERPRINT_CORRECT
        static_cast<Effect>(157), // EFFECT_OOS_KEYBOARD_WEAK
        static_cast<Effect>(158), // EFFECT_OOS_KEYBOARD_MEDIUM
        static_cast<Effect>(159), // EFFECT_OOS_KEYBOARD_STRONG
        static_cast<Effect>(308), // EFFECT_OOS_ALERTSLIDER_DOWN
        static_cast<Effect>(368), // EFFECT_OOS_VIRTUAL_KEY
        static_cast<Effect>(409), // EFFECT_OOS_SCREENSHOT
        static_cast<Effect>(430), // EFFECT_OOS_THREE_STAGE_KEY
    };

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::setAmplitude(float amplitude) {
    uint8_t tmp = (uint8_t)(amplitude * 0xff);

    int32_t ret = aac_vibra_setAmplitude(tmp);
    if (ret) {
        ALOGE("AAC set amplitude failed: %d\n", ret);
        return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_SERVICE_SPECIFIC));
    }

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::setExternalControl(bool enabled __unused) {
    return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_UNSUPPORTED_OPERATION));
}

ndk::ScopedAStatus Vibrator::getCompositionDelayMax(int32_t* maxDelayMs) {
    *maxDelayMs = 5000;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getCompositionSizeMax(int32_t* maxSize) {
    *maxSize = 100;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getSupportedPrimitives(
        std::vector<CompositePrimitive>* supported) {
    *supported = {
        CompositePrimitive::CLICK,
        CompositePrimitive::THUD,
        CompositePrimitive::SPIN,
        CompositePrimitive::QUICK_RISE,
        CompositePrimitive::SLOW_RISE,
        CompositePrimitive::QUICK_FALL,
        CompositePrimitive::LIGHT_TICK,
        CompositePrimitive::LOW_TICK,
    };
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getPrimitiveDuration(CompositePrimitive primitive,
                                                  int32_t* durationMs) {
    switch (primitive) {
        case CompositePrimitive::CLICK:
        case CompositePrimitive::SPIN:
        case CompositePrimitive::QUICK_FALL:
            *durationMs = 28;
            break;
        case CompositePrimitive::THUD:
            *durationMs = 82;
            break;
        case CompositePrimitive::QUICK_RISE:
            *durationMs = 15;
            break;
        case CompositePrimitive::SLOW_RISE:
            *durationMs = 45;
            break;
        case CompositePrimitive::LIGHT_TICK:
        case CompositePrimitive::LOW_TICK:
        default:
            *durationMs = 11;
            break;
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::compose(const std::vector<CompositeEffect>& composite,
                                     const std::shared_ptr<IVibratorCallback>& callback) {
    if (composite.empty()) {
        return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_ILLEGAL_ARGUMENT));
    }
    if (composite.size() > 100) {
        return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_ILLEGAL_ARGUMENT));
    }

    std::thread([=]() {
        for (const auto& effect : composite) {
            if (effect.delayMs > 0) {
                usleep(effect.delayMs * 1000);
            }

            uint32_t aac_effect = 0x1002;
            int32_t strength = RICHTAP_MEDIUM_STRENGTH;

            if (effect.scale <= 0.35f) {
                strength = RICHTAP_LIGHT_STRENGTH;
            } else if (effect.scale >= 0.75f) {
                strength = RICHTAP_STRONG_STRENGTH;
            }

            switch (effect.primitive) {
                case CompositePrimitive::CLICK:
                case CompositePrimitive::SPIN:
                    aac_effect = 0x1002;
                    break;
                case CompositePrimitive::THUD:
                    aac_effect = 0x1001;
                    strength = RICHTAP_STRONG_STRENGTH;
                    break;
                case CompositePrimitive::QUICK_FALL:
                    aac_effect = 0x1002;
                    strength = RICHTAP_STRONG_STRENGTH;
                    break;
                case CompositePrimitive::QUICK_RISE:
                    aac_effect = 0x1002;
                    strength = RICHTAP_MEDIUM_STRENGTH;
                    break;
                case CompositePrimitive::SLOW_RISE:
                    aac_effect = 0x1001;
                    strength = RICHTAP_MEDIUM_STRENGTH;
                    break;
                case CompositePrimitive::LIGHT_TICK:
                case CompositePrimitive::LOW_TICK:
                default:
                    aac_effect = 0x1000;
                    strength = RICHTAP_LIGHT_STRENGTH;
                    break;
            }

            int32_t playRet = aac_vibra_looper_prebaked_effect(aac_effect, strength);
            int32_t waitTime = playRet > 0 ? playRet : 20;
            usleep(waitTime * 1000);
        }

        if (callback != nullptr) {
            callback->onComplete();
        }
    }).detach();

    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Vibrator::getSupportedAlwaysOnEffects(
        std::vector<Effect>* _aidl_return __unused) {
    return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_UNSUPPORTED_OPERATION));
}

ndk::ScopedAStatus Vibrator::alwaysOnEnable(int32_t id __unused, Effect effect __unused,
                                            EffectStrength strength __unused) {
    return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_UNSUPPORTED_OPERATION));
}

ndk::ScopedAStatus Vibrator::alwaysOnDisable(int32_t id __unused) {
    return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_UNSUPPORTED_OPERATION));
}

ndk::ScopedAStatus Vibrator::getResonantFrequency(float* resonantFreqHz __unused) {
    return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_UNSUPPORTED_OPERATION));
}

ndk::ScopedAStatus Vibrator::getQFactor(float* qFactor __unused) {
    return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_UNSUPPORTED_OPERATION));
}

ndk::ScopedAStatus Vibrator::getFrequencyResolution(float* freqResolutionHz __unused) {
    return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_UNSUPPORTED_OPERATION));
}

ndk::ScopedAStatus Vibrator::getFrequencyMinimum(float* freqMinimumHz __unused) {
    return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_UNSUPPORTED_OPERATION));
}

ndk::ScopedAStatus Vibrator::getBandwidthAmplitudeMap(std::vector<float>* _aidl_return __unused) {
    return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_UNSUPPORTED_OPERATION));
}

ndk::ScopedAStatus Vibrator::getPwlePrimitiveDurationMax(int32_t* durationMs __unused) {
    return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_UNSUPPORTED_OPERATION));
}

ndk::ScopedAStatus Vibrator::getPwleCompositionSizeMax(int32_t* maxSize __unused) {
    return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_UNSUPPORTED_OPERATION));
}

ndk::ScopedAStatus Vibrator::getSupportedBraking(std::vector<Braking>* supported __unused) {
    return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_UNSUPPORTED_OPERATION));
}

ndk::ScopedAStatus Vibrator::composePwle(const std::vector<PrimitivePwle>& composite __unused,
                                         const std::shared_ptr<IVibratorCallback>& callback
                                                 __unused) {
    return ndk::ScopedAStatus(AStatus_fromExceptionCode(EX_UNSUPPORTED_OPERATION));
}

}  // namespace vibrator
}  // namespace hardware
}  // namespace android
}  // namespace aidl
