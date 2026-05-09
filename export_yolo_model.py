#!/usr/bin/env python3
"""Export YOLOv8n to ONNX format for the StreamCam Android app.

Prerequisites:
    pip install ultralytics

Usage:
    python export_yolo_model.py

The script downloads YOLOv8n, exports it to ONNX (320x320 input),
and copies the result to app/src/main/assets/yolov8n.onnx.
"""

import shutil
from pathlib import Path

from ultralytics import YOLO

model = YOLO("yolov8n.pt")
exported = model.export(format="onnx", imgsz=320, simplify=True)

dest = Path("app/src/main/assets/yolov8n.onnx")
dest.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(exported, dest)
print(f"Model copied to {dest} ({dest.stat().st_size / 1024 / 1024:.1f} MB)")
