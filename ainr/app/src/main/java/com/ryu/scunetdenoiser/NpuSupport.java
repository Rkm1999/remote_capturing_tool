package com.ryu.scunetdenoiser;

import android.os.Build;

import com.google.ai.edge.litert.NpuCompatibilityChecker;

import java.util.Locale;

final class NpuSupport {
    enum Vendor {
        QUALCOMM,
        MEDIATEK,
        SAMSUNG,
        UNSUPPORTED
    }

    private NpuSupport() {}

    static Vendor detect() {
        if (Build.VERSION.SDK_INT < 31) return Vendor.UNSUPPORTED;

        String manufacturer = clean(Build.SOC_MANUFACTURER).toLowerCase(Locale.US);
        String model = clean(Build.SOC_MODEL).toUpperCase(Locale.US);
        if ((NpuCompatibilityChecker.Companion.getQualcomm().isDeviceSupported()
                || manufacturer.equals("qualcomm")
                || manufacturer.equals("qti"))
            && qualcommHtpArchitecture(model) != null) {
            return Vendor.QUALCOMM;
        }
        if (NpuCompatibilityChecker.Companion.getMediatek().isDeviceSupported()
            || (manufacturer.equals("mediatek") && isDocumentedMediaTekSoc(model))) {
            return Vendor.MEDIATEK;
        }

        if (manufacturer.equals("samsung")
            && (model.endsWith("E9955") || model.endsWith("E9965"))) {
            return Vendor.SAMSUNG;
        }
        return Vendor.UNSUPPORTED;
    }

    static String displayName(Vendor vendor) {
        if (vendor == Vendor.QUALCOMM) {
            String architecture = qualcommHtpArchitecture();
            return architecture == null
                ? "Qualcomm HTP"
                : "Qualcomm HTP " + architecture;
        }
        if (vendor == Vendor.MEDIATEK) return "MediaTek NeuroPilot";
        if (vendor == Vendor.SAMSUNG) return "Samsung AI LiteCore";
        return "Unsupported NPU";
    }

    static String qualcommHtpArchitecture() {
        if (Build.VERSION.SDK_INT < 31) return null;
        return qualcommHtpArchitecture(clean(Build.SOC_MODEL).toUpperCase(Locale.US));
    }

    static String qualcommHtpArchitecture(String model) {
        if (model == null) return null;
        switch (model.toUpperCase(Locale.US)) {
            case "SM8450":
            case "SM8475":
                return "V69";
            case "SM8550":
                return "V73";
            case "SM8650":
                return "V75";
            case "SM8750":
                return "V79";
            case "SM8850":
                return "V81";
            default:
                return null;
        }
    }

    static String deviceSummary() {
        if (Build.VERSION.SDK_INT < 31) return "Android " + Build.VERSION.SDK_INT;
        String manufacturer = clean(Build.SOC_MANUFACTURER);
        String model = clean(Build.SOC_MODEL);
        String value = (manufacturer + " " + model).trim();
        return value.isEmpty() ? "unknown SoC" : value;
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace("(ENG)", "").trim();
    }

    private static boolean isDocumentedMediaTekSoc(String model) {
        return model.equals("MT6878")
            || model.equals("MT6897")
            || model.equals("MT6983")
            || model.equals("MT6985")
            || model.equals("MT6989")
            || model.equals("MT6990")
            || model.equals("MT6991")
            || model.equals("MT6993");
    }
}
