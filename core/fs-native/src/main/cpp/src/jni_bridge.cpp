#include <jni.h>
#include <string>
#include <android/log.h>
#include "FatFormatter.hpp"
#include "ExFatFormatter.hpp"
#include "Ext4Formatter.hpp"

#define TAG "UsbAdvanceNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using namespace usbadvance;

extern "C" JNIEXPORT jboolean JNICALL
Java_org_usbadvance_core_fs_nativebridge_NativeFormatBridge_nativeFormat(
    JNIEnv* env,
    jobject /* thiz */,
    jint fs_type_ordinal,
    jlong start_lba,
    jlong sector_count,
    jint sector_size,
    jint cluster_size_bytes,
    jstring volume_label,
    jboolean quick_format,
    jboolean disable_journal,
    jobject io_callback
) {
    std::string label = "";
    if (volume_label != nullptr) {
        const char* label_cstr = env->GetStringUTFChars(volume_label, nullptr);
        if (label_cstr) {
            label = label_cstr;
            env->ReleaseStringUTFChars(volume_label, label_cstr);
        }
    }

    NativeFormatParams params{
        .start_lba = static_cast<uint64_t>(start_lba),
        .sector_count = static_cast<uint64_t>(sector_count),
        .sector_size = static_cast<uint32_t>(sector_size),
        .cluster_size_bytes = static_cast<uint32_t>(cluster_size_bytes),
        .volume_label = label,
        .quick_format = static_cast<bool>(quick_format),
        .disable_journal = static_cast<bool>(disable_journal)
    };

    jclass callback_class = env->GetObjectClass(io_callback);
    jmethodID write_mid = env->GetMethodID(callback_class, "onWriteSectors", "(JI[B)Z");
    jmethodID progress_mid = env->GetMethodID(callback_class, "onProgress", "(FLjava/lang/String;)V");

    if (!write_mid || !progress_mid) {
        LOGE("Falha ao localizar métodos de callback JNI.");
        return JNI_FALSE;
    }

    WriteSectorsFn write_fn = [&](uint64_t lba, uint32_t count, const uint8_t* data) -> bool {
        jsize total_len = static_cast<jsize>(count * params.sector_size);
        jbyteArray byte_array = env->NewByteArray(total_len);
        if (!byte_array) return false;

        env->SetByteArrayRegion(byte_array, 0, total_len, reinterpret_cast<const jbyte*>(data));
        jboolean result = env->CallBooleanMethod(
            io_callback,
            write_mid,
            static_cast<jlong>(lba),
            static_cast<jint>(count),
            byte_array
        );
        env->DeleteLocalRef(byte_array);

        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return false;
        }
        return (result == JNI_TRUE);
    };

    ProgressFn progress_fn = [&](float percentage, const std::string& description) {
        jstring jdesc = env->NewStringUTF(description.c_str());
        env->CallVoidMethod(io_callback, progress_mid, static_cast<jfloat>(percentage), jdesc);
        if (jdesc) env->DeleteLocalRef(jdesc);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    };

    bool success = false;
    switch (fs_type_ordinal) {
        case 0: // FAT16
            success = FatFormatter::formatFat16(params, write_fn, progress_fn);
            break;
        case 1: // FAT32
            success = FatFormatter::formatFat32(params, write_fn, progress_fn);
            break;
        case 2: // exFAT
            success = ExFatFormatter::format(params, write_fn, progress_fn);
            break;
        case 3: // ext4
            success = Ext4Formatter::format(params, write_fn, progress_fn);
            break;
        default:
            LOGE("Tipo de sistema de arquivos desconhecido: %d", fs_type_ordinal);
            return JNI_FALSE;
    }

    return success ? JNI_TRUE : JNI_FALSE;
}
