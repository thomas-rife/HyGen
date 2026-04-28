package com.example.exampleplugin.levels.model;

public class TransformData {
    public Vector3Data position;
    public float yaw;
    public float pitch;
    public float roll;

    public TransformData() {
    }

    public TransformData(Vector3Data position, float yaw, float pitch, float roll) {
        this.position = position;
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
    }
}
