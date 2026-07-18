package com.ryu.scunetdenoiser;

import android.content.Context;
import android.system.Os;

import com.google.ai.edge.litert.Accelerator;
import com.google.ai.edge.litert.CompiledModel;
import com.google.ai.edge.litert.Environment;
import com.google.ai.edge.litert.TensorBuffer;

import java.io.File;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class AcceleratorEngine implements AutoCloseable {
    enum Kind {
        GPU,
        NPU
    }

    private static final int TENSOR_ELEMENTS = 3 * 192 * 192;

    private final Kind kind;
    private final Environment environment;
    private final CompiledModel model;
    private final List<TensorBuffer> inputs;
    private final List<TensorBuffer> outputs;
    private boolean closed;

    private AcceleratorEngine(
        Kind kind,
        Environment environment,
        CompiledModel model,
        List<TensorBuffer> inputs,
        List<TensorBuffer> outputs
    ) {
        this.kind = kind;
        this.environment = environment;
        this.model = model;
        this.inputs = inputs;
        this.outputs = outputs;
    }

    static AcceleratorEngine createGpu(
        Context context,
        File modelFile,
        String modelCacheKey
    ) throws Exception {
        File cacheDir = new File(context.getCacheDir(), "litert_gpu");
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            throw new IllegalStateException("Could not create GPU cache directory");
        }
        CompiledModel.Options options = new CompiledModel.Options(Accelerator.GPU);
        options.setGpuOptions(new CompiledModel.GpuOptions(
            null,
            null,
            null,
            CompiledModel.GpuOptions.Precision.FP16,
            CompiledModel.GpuOptions.BufferStorageType.DEFAULT,
            null,
            cacheDir.getAbsolutePath(),
            "scunet_192_" + modelCacheKey.substring(0, 16),
            Boolean.TRUE,
            null,
            null,
            null,
            CompiledModel.GpuOptions.Backend.OPENCL,
            CompiledModel.GpuOptions.Priority.HIGH,
            null
        ));
        CompiledModel model = CompiledModel.create(modelFile.getAbsolutePath(), options, null);
        return createBuffers(Kind.GPU, null, model);
    }

    static AcceleratorEngine createNpu(
        Context context,
        File modelFile,
        String modelCacheKey
    ) throws Exception {
        NpuSupport.Vendor vendor = NpuSupport.detect();
        if (vendor == NpuSupport.Vendor.UNSUPPORTED) {
            throw new IllegalStateException(
                "NPU is not supported on " + NpuSupport.deviceSummary());
        }
        File pluginDir = preparePluginDirectory(context, vendor);
        if (vendor == NpuSupport.Vendor.QUALCOMM) {
            // Cache-key generation queries QNN before LiteRT configures FastRPC.
            // Expose the packaged DSP image early so that query can succeed.
            Os.setenv("ADSP_LIBRARY_PATH", pluginDir.getAbsolutePath(), true);
            System.loadLibrary("QnnSystem");
            System.loadLibrary("QnnHtp");
        }
        Map<Environment.Option, String> environmentOptions =
            new EnumMap<>(Environment.Option.class);
        environmentOptions.put(
            Environment.Option.CompilerPluginLibraryDir,
            pluginDir.getAbsolutePath());
        environmentOptions.put(
            Environment.Option.DispatchLibraryDir,
            pluginDir.getAbsolutePath());
        Environment environment = CachedEnvironmentFactory.create(
            context,
            environmentOptions,
            "litert-2.1.6-" + modelCacheKey.substring(0, 16));
        try {
            CompiledModel.Options options = new CompiledModel.Options(Accelerator.NPU);
            if (vendor == NpuSupport.Vendor.QUALCOMM) {
                options.setQualcommOptions(new CompiledModel.QualcommOptions(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    CompiledModel.QualcommOptions.HtpPerformanceMode.BURST,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
                ));
            }
            CompiledModel model = CompiledModel.create(
                modelFile.getAbsolutePath(), options, environment);
            return createBuffers(Kind.NPU, environment, model);
        } catch (Throwable error) {
            environment.close();
            throw error;
        }
    }

    private static AcceleratorEngine createBuffers(
        Kind kind,
        Environment environment,
        CompiledModel model
    ) throws Exception {
        try {
            List<TensorBuffer> inputs = model.createInputBuffers();
            List<TensorBuffer> outputs = model.createOutputBuffers();
            if (inputs.size() != 1 || outputs.size() != 1) {
                closeBuffers(inputs);
                closeBuffers(outputs);
                throw new IllegalStateException(
                    "SCUNet expected one input and output, got "
                        + inputs.size() + " and " + outputs.size());
            }
            return new AcceleratorEngine(kind, environment, model, inputs, outputs);
        } catch (Throwable error) {
            model.close();
            throw error;
        }
    }

    Kind kind() {
        return kind;
    }

    void warmUp() throws Exception {
        infer(new float[TENSOR_ELEMENTS]);
    }

    float[] infer(float[] input) throws Exception {
        if (closed) throw new IllegalStateException(kind + " engine is closed");
        if (input.length != TENSOR_ELEMENTS) {
            throw new IllegalArgumentException("Unexpected tensor size " + input.length);
        }
        inputs.get(0).writeFloat(input);
        model.run(inputs, outputs);
        return outputs.get(0).readFloat();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        closeBuffers(inputs);
        closeBuffers(outputs);
        model.close();
        if (environment != null) environment.close();
    }

    private static void closeBuffers(List<TensorBuffer> buffers) {
        if (buffers == null) return;
        for (TensorBuffer buffer : buffers) buffer.close();
    }

    private static File preparePluginDirectory(
        Context context,
        NpuSupport.Vendor vendor
    ) throws Exception {
        String vendorSuffix;
        if (vendor == NpuSupport.Vendor.QUALCOMM) {
            vendorSuffix = "Qualcomm";
        } else if (vendor == NpuSupport.Vendor.MEDIATEK) {
            vendorSuffix = "MediaTek";
        } else if (vendor == NpuSupport.Vendor.SAMSUNG) {
            vendorSuffix = "Samsung";
        } else {
            throw new IllegalArgumentException("Unsupported NPU vendor " + vendor);
        }

        File pluginDir = new File(
            context.getCodeCacheDir(),
            "litert_plugins_v216/" + vendor.name().toLowerCase(Locale.US));
        if (!pluginDir.isDirectory() && !pluginDir.mkdirs()) {
            throw new IllegalStateException("Could not create LiteRT plugin directory");
        }

        File[] staleEntries = pluginDir.listFiles();
        if (staleEntries != null) {
            for (File entry : staleEntries) {
                if (!entry.delete()) {
                    throw new IllegalStateException(
                        "Could not clear stale LiteRT plugin " + entry.getName());
                }
            }
        }

        linkPlugin(context, pluginDir, "libLiteRtCompilerPlugin_" + vendorSuffix + ".so");
        linkPlugin(context, pluginDir, "libLiteRtDispatch_" + vendorSuffix + ".so");
        if (vendor == NpuSupport.Vendor.QUALCOMM) {
            String htpArchitecture = NpuSupport.qualcommHtpArchitecture();
            if (htpArchitecture == null) {
                throw new IllegalStateException(
                    "No Qualcomm HTP runtime for " + NpuSupport.deviceSummary());
            }
            // FastRPC searches the configured plugin path for the HTP DSP image.
            // Keep the complete Qualcomm runtime beside the filtered plugins.
            linkPlugin(context, pluginDir, "libQnnSystem.so");
            linkPlugin(context, pluginDir, "libQnnHtp.so");
            linkPlugin(context, pluginDir, "libQnnHtpPrepare.so");
            linkPlugin(
                context,
                pluginDir,
                "libQnnHtp" + htpArchitecture + "Skel.so");
            linkPlugin(
                context,
                pluginDir,
                "libQnnHtp" + htpArchitecture + "Stub.so");
            linkPlugin(context, pluginDir, "libQnnIr.so");
            linkPlugin(context, pluginDir, "libQnnSaver.so");
        }
        return pluginDir;
    }

    private static void linkPlugin(Context context, File pluginDir, String libraryName)
        throws Exception {
        File source = new File(context.getApplicationInfo().nativeLibraryDir, libraryName);
        if (!source.isFile()) {
            throw new IllegalStateException("Missing packaged NPU plugin " + libraryName);
        }
        File link = new File(pluginDir, libraryName);
        Os.symlink(source.getAbsolutePath(), link.getAbsolutePath());
    }
}
