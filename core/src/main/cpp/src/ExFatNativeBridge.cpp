#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "ExFatVfsEngine.hpp"

#define TAG "ExFatNativeBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using namespace usbadvance;

static std::string jstringToStdString(JNIEnv* env, jstring jstr) {
    if (!jstr) return "";
    const char* cstr = env->GetStringUTFChars(jstr, nullptr);
    if (!cstr) return "";
    std::string str(cstr);
    env->ReleaseStringUTFChars(jstr, cstr);
    return str;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_org_usbadvance_core_fs_nativebridge_ExFatNativeBridge_nativeMount(
    JNIEnv* env,
    jobject /* thiz */,
    jlong start_lba,
    jlong total_sectors,
    jint sector_size,
    jobject block_reader
) {
    if (!block_reader) {
        LOGE("nativeMount: block_reader is null");
        return 0;
    }

    jclass reader_class = env->GetObjectClass(block_reader);
    jmethodID read_mid = env->GetMethodID(reader_class, "onReadSectors", "(JILjava/nio/ByteBuffer;)Z");
    if (!read_mid) {
        LOGE("nativeMount: Failed to find onReadSectors method");
        return 0;
    }

    // Keep global ref to reader callback
    jobject global_reader = env->NewGlobalRef(block_reader);
    JavaVM* jvm = nullptr;
    env->GetJavaVM(&jvm);

    auto engine = std::make_unique<ExFatVfsEngine>();

    ReadSectorsFn read_fn = [jvm, global_reader, read_mid, sector_size](
        uint64_t lba, uint32_t count, uint8_t* destination
    ) -> bool {
        JNIEnv* thread_env = nullptr;
        bool must_detach = false;
        if (jvm->GetEnv(reinterpret_cast<void**>(&thread_env), JNI_VERSION_1_6) != JNI_OK) {
            if (jvm->AttachCurrentThread(&thread_env, nullptr) != JNI_OK) {
                return false;
            }
            must_detach = true;
        }

        jlong byte_capacity = static_cast<jlong>(count) * sector_size;
        jobject direct_buf = thread_env->NewDirectByteBuffer(destination, byte_capacity);
        if (!direct_buf) {
            if (must_detach) jvm->DetachCurrentThread();
            return false;
        }

        jboolean res = thread_env->CallBooleanMethod(
            global_reader,
            read_mid,
            static_cast<jlong>(lba),
            static_cast<jint>(count),
            direct_buf
        );

        thread_env->DeleteLocalRef(direct_buf);

        if (thread_env->ExceptionCheck()) {
            thread_env->ExceptionDescribe();
            thread_env->ExceptionClear();
            res = JNI_FALSE;
        }

        if (must_detach) {
            jvm->DetachCurrentThread();
        }

        return (res == JNI_TRUE);
    };

    if (!engine->mount(read_fn, static_cast<uint64_t>(start_lba), static_cast<uint64_t>(total_sectors))) {
        env->DeleteGlobalRef(global_reader);
        return 0;
    }

    return reinterpret_cast<jlong>(engine.release());
}

JNIEXPORT void JNICALL
Java_org_usbadvance_core_fs_nativebridge_ExFatNativeBridge_nativeUnmount(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle
) {
    if (handle != 0) {
        auto* engine = reinterpret_cast<ExFatVfsEngine*>(handle);
        delete engine;
    }
}

JNIEXPORT jstring JNICALL
Java_org_usbadvance_core_fs_nativebridge_ExFatNativeBridge_nativeGetVolumeLabel(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle
) {
    if (handle == 0) return env->NewStringUTF("USB ADVANCE");
    auto* engine = reinterpret_cast<ExFatVfsEngine*>(handle);
    return env->NewStringUTF(engine->getVolumeLabel().c_str());
}

JNIEXPORT jint JNICALL
Java_org_usbadvance_core_fs_nativebridge_ExFatNativeBridge_nativeGetClusterSize(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle
) {
    if (handle == 0) return 0;
    auto* engine = reinterpret_cast<ExFatVfsEngine*>(handle);
    return static_cast<jint>(engine->getClusterSize());
}

JNIEXPORT jint JNICALL
Java_org_usbadvance_core_fs_nativebridge_ExFatNativeBridge_nativeGetSectorSize(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle
) {
    if (handle == 0) return 0;
    auto* engine = reinterpret_cast<ExFatVfsEngine*>(handle);
    return static_cast<jint>(engine->getSectorSize());
}

JNIEXPORT jboolean JNICALL
Java_org_usbadvance_core_fs_nativebridge_ExFatNativeBridge_nativeListDirectory(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring jpath,
    jobject visitor
) {
    if (handle == 0 || !visitor) return JNI_FALSE;
    auto* engine = reinterpret_cast<ExFatVfsEngine*>(handle);

    std::string path = jstringToStdString(env, jpath);
    std::vector<ExFatNode> entries;
    if (!engine->listDirectory(path, entries)) {
        return JNI_FALSE;
    }

    jclass visitor_class = env->GetObjectClass(visitor);
    jmethodID on_entry_mid = env->GetMethodID(
        visitor_class,
        "onEntry",
        "(Ljava/lang/String;JZJZZZZ)V"
    );

    if (!on_entry_mid) {
        LOGE("nativeListDirectory: visitor missing onEntry method");
        return JNI_FALSE;
    }

    for (const auto& entry : entries) {
        jstring jname = env->NewStringUTF(entry.name.c_str());
        env->CallVoidMethod(
            visitor,
            on_entry_mid,
            jname,
            static_cast<jlong>(entry.size_bytes),
            static_cast<jboolean>(entry.is_directory),
            static_cast<jlong>(entry.modified_epoch_ms),
            static_cast<jboolean>(entry.no_fat_chain),
            static_cast<jlong>(entry.first_cluster),
            static_cast<jboolean>(entry.is_read_only),
            static_cast<jboolean>(entry.is_hidden),
            static_cast<jboolean>(entry.is_system)
        );
        env->DeleteLocalRef(jname);

        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return JNI_FALSE;
        }
    }

    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_org_usbadvance_core_fs_nativebridge_ExFatNativeBridge_nativeReadFile(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jlong first_cluster,
    jlong file_size,
    jboolean no_fat_chain,
    jlong file_offset,
    jobject destination_buffer,
    jint buffer_offset,
    jint read_length
) {
    if (handle == 0 || !destination_buffer || read_length <= 0) {
        return -1;
    }

    auto* engine = reinterpret_cast<ExFatVfsEngine*>(handle);

    auto* direct_address = static_cast<uint8_t*>(env->GetDirectBufferAddress(destination_buffer));
    if (!direct_address) {
        LOGE("nativeReadFile: destination_buffer is not a direct buffer!");
        return -1;
    }

    jlong buffer_capacity = env->GetDirectBufferCapacity(destination_buffer);
    if (buffer_offset + read_length > buffer_capacity) {
        LOGE("nativeReadFile: buffer overflow detected (%d + %d > %lld)",
             buffer_offset, read_length, (long long)buffer_capacity);
        return -1;
    }

    ExFatNode node;
    node.first_cluster = static_cast<uint32_t>(first_cluster);
    node.size_bytes = static_cast<uint64_t>(file_size);
    node.no_fat_chain = static_cast<bool>(no_fat_chain);

    int64_t bytes_read = engine->readFile(
        node,
        static_cast<uint64_t>(file_offset),
        direct_address + buffer_offset,
        static_cast<size_t>(read_length)
    );

    return static_cast<jint>(bytes_read);
}

JNIEXPORT jboolean JNICALL
Java_org_usbadvance_core_fs_nativebridge_ExFatNativeBridge_nativeAnalyzeContiguity(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jlong first_cluster,
    jlong file_size,
    jboolean no_fat_chain,
    jobject visitor
) {
    if (handle == 0 || !visitor) return JNI_FALSE;
    auto* engine = reinterpret_cast<ExFatVfsEngine*>(handle);

    ExFatNode node;
    node.first_cluster = static_cast<uint32_t>(first_cluster);
    node.size_bytes = static_cast<uint64_t>(file_size);
    node.no_fat_chain = static_cast<bool>(no_fat_chain);

    std::vector<ClusterExtent> extents;
    bool is_contiguous = false;
    if (!engine->analyzeContiguity(node, extents, is_contiguous)) {
        return JNI_FALSE;
    }

    jclass visitor_class = env->GetObjectClass(visitor);
    jmethodID on_extent_mid = env->GetMethodID(visitor_class, "onExtent", "(JJJ)V");
    if (!on_extent_mid) {
        LOGE("nativeAnalyzeContiguity: visitor missing onExtent method");
        return JNI_FALSE;
    }

    for (const auto& ext : extents) {
        uint64_t lba = engine->getClusterSize() > 0 ?
            (ext.start_cluster >= 2 ? (static_cast<uint64_t>(ext.start_cluster - 2) * (engine->getClusterSize() / engine->getSectorSize())) : 0) : 0;

        env->CallVoidMethod(
            visitor,
            on_extent_mid,
            static_cast<jlong>(ext.start_cluster),
            static_cast<jlong>(ext.cluster_count),
            static_cast<jlong>(lba)
        );

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return JNI_FALSE;
        }
    }

    return JNI_TRUE;
}

} // extern "C"
