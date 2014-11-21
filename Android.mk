LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_JAVA_LIBRARIES := droidlogic
LOCAL_SDK_VERSION := current

LOCAL_PACKAGE_NAME := HdmiIn
LOCAL_CERTIFICATE := platform
LOCAL_ARM_MODE := arm

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
