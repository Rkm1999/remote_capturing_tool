#!/usr/bin/env python3
"""Build delegate-specific INT8 SCUNet models for Android."""

from pathlib import Path

import numpy as np
from tensorflow.lite.python import schema_py_generated as schema
from tensorflow.lite.tools import flatbuffer_utils


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/assets/models/scunet_192_fp16w.tflite"
GPU_DESTINATION = ROOT / "app/src/main/assets/models/scunet_192_int8w_gpu.tflite"
NPU_DESTINATION = ROOT / "app/src/main/assets/models/scunet_192_int8w_npu.tflite"


def quantize(values: np.ndarray) -> tuple[np.ndarray, float]:
    maximum = float(np.max(np.abs(values)))
    scale = maximum / 127.0 if maximum > 0.0 else 1.0
    output = np.clip(np.rint(values / scale), -127, 127).astype(np.int8)
    return output, scale


def set_int8(tensor, buffer, values: np.ndarray) -> None:
    output, scale = quantize(values)
    buffer.data = np.frombuffer(output.tobytes(), dtype=np.uint8)
    tensor.type = schema.TensorType.INT8
    tensor.quantization = schema.QuantizationParametersT()
    tensor.quantization.scale = [scale]
    tensor.quantization.zeroPoint = [0]
    tensor.quantization.quantizedDimension = 0


def main() -> None:
    npu_model = flatbuffer_utils.read_model(str(SOURCE))
    npu_converted = 0
    for subgraph in npu_model.subgraphs:
        for tensor in subgraph.tensors:
            if tensor.type != schema.TensorType.FLOAT16 or tensor.buffer == 0:
                continue
            buffer = npu_model.buffers[tensor.buffer]
            if buffer.data is None or len(buffer.data) == 0:
                continue
            values = np.frombuffer(buffer.data, dtype=np.float16).astype(np.float32)
            set_int8(tensor, buffer, values)
            npu_converted += 1
    flatbuffer_utils.write_model(npu_model, str(NPU_DESTINATION))

    model = flatbuffer_utils.read_model(str(SOURCE))
    normalized = 0
    for subgraph in model.subgraphs:
        replacements: dict[int, int] = {}
        retained = []
        for operation in subgraph.operators:
            opcode = model.operatorCodes[operation.opcodeIndex].builtinCode
            if opcode != schema.BuiltinOperator.DEQUANTIZE:
                retained.append(operation)
                continue
            source_index = operation.inputs[0]
            destination_index = operation.outputs[0]
            source = subgraph.tensors[source_index]
            if source.type != schema.TensorType.FLOAT16 or source.buffer == 0:
                retained.append(operation)
                continue
            values = np.frombuffer(
                model.buffers[source.buffer].data, dtype=np.float16
            ).astype(np.float32)
            model.buffers[source.buffer].data = np.frombuffer(
                values.tobytes(), dtype=np.uint8
            )
            source.type = schema.TensorType.FLOAT32
            replacements[destination_index] = source_index
            normalized += 1

        if replacements:
            for operation in retained:
                operation.inputs = [replacements.get(index, index) for index in operation.inputs]
            subgraph.outputs = [replacements.get(index, index) for index in subgraph.outputs]
            subgraph.operators = retained

    converted = 0
    for subgraph in model.subgraphs:
        for operation in subgraph.operators:
            opcode = model.operatorCodes[operation.opcodeIndex].builtinCode
            if opcode in (
                schema.BuiltinOperator.CONV_2D,
                schema.BuiltinOperator.FULLY_CONNECTED,
                schema.BuiltinOperator.BATCH_MATMUL,
            ):
                weight_index = operation.inputs[1]
            elif opcode == schema.BuiltinOperator.TRANSPOSE_CONV:
                weight_index = operation.inputs[1]
            else:
                continue
            tensor = subgraph.tensors[weight_index]
            if tensor.type != schema.TensorType.FLOAT32 or tensor.buffer == 0:
                continue
            buffer = model.buffers[tensor.buffer]
            if buffer.data is None or len(buffer.data) == 0:
                continue
            values = np.frombuffer(buffer.data, dtype=np.float32)
            set_int8(tensor, buffer, values)
            converted += 1

    if npu_converted == 0 or normalized == 0 or converted == 0:
        raise RuntimeError("Expected FP16 dequantize nodes and constant weights")
    flatbuffer_utils.write_model(model, str(GPU_DESTINATION))
    print(
        f"NPU: converted {npu_converted} explicit constants: {NPU_DESTINATION}\n"
        f"GPU: removed {normalized} dequantize nodes and converted "
        f"{converted} filter tensors: {GPU_DESTINATION}"
    )


if __name__ == "__main__":
    main()
