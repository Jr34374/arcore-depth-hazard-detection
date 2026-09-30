#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
precision highp usampler2D;

in vec2 v_TexCoord;
uniform samplerExternalOES u_CameraTexture;
uniform usampler2D u_DepthTexture;
//out vec4 fragColor;
layout(location = 0) out vec4 fragColor;

// Google's Turbo Colormap
// 引用元: https://ai.googleblog.com/2019/08/turbo-improved-rainbow-colormap-for.html
vec3 turbo(float x) {
    // x (0.0-1.0) を入力として、RGB (0.0-1.0) の色を返す
    vec4 x_vec = vec4(1.0, x, x * x, x * x * x);
    vec4 r_vec = vec4(0.13572138, 4.6153326, -42.63615, 87.251915);
    vec4 g_vec = vec4(0.09140261, 2.144222, 4.814334, -23.41024);
    vec4 b_vec = vec4(0.10667277, 12.134833, -55.90623, 85.45423);
    float r = dot(x_vec, r_vec);
    float g = dot(x_vec, g_vec);
    float b = dot(x_vec, b_vec);
    return clamp(vec3(r, g, b), 0.0, 1.0);
}

// 2つの値の間で、ある値がどの位置にあるかを0.0-1.0で返す
float InverseLerp(float value, float min_bound, float max_bound) {
    return clamp((value - min_bound) / (max_bound - min_bound), 0.0, 1.0);
}

void main() {
    vec4 cameraColor = texture(u_CameraTexture, v_TexCoord);

    uint rawDepth = texture(u_DepthTexture, v_TexCoord).r;
    float depthMeters = float(rawDepth) / 1000.0;


    // hello_arサンプルを参考に2段階正規化
    // 近距離と遠距離の境界を：kMidDepthMeter：mに設定 可変
    const float kMidDepthMeters = 3.5;
    const float kMaxDepthMeters = 20.0;

    float normalizedDepth = 0.0;
    if (depthMeters < kMidDepthMeters) {
        // 近距離(0m-4m)の範囲を、カラーマップの前半(0.0-0.5)に割り当てる
        normalizedDepth = InverseLerp(depthMeters, 0.0, kMidDepthMeters) * 0.5;
    } else {
        // 遠距離(4m-20m)の範囲を、カラーマップの後半(0.5-1.0)に割り当てる
        normalizedDepth = InverseLerp(depthMeters, kMidDepthMeters, kMaxDepthMeters) * 0.5 + 0.5;
    }


    if (rawDepth > 0u) {
        //1.0 - normalizedDepth: 0.0(近い) -> 暖色(赤), 1.0(遠い) -> 寒色(青)
        vec3 depthColor = turbo(1.0 - normalizedDepth);

        // ヒートマップの表示を少し強くするため、ブレンド率を75%
        fragColor = mix(cameraColor, vec4(depthColor, 1.0), 0.75);
    } else {
        // 深度が0のピクセルはカメラ画像をそのまま表示
        fragColor = cameraColor;
    }

    // デバッグ用: 画面中央に白い十字線を描画
    // v_TexCoord は (0.0, 0.0) [左下] から (1.0, 1.0) [右上] の値
    // (※ARCoreのテクスチャ座標は環境によるが、ここでは中央を 0.5 と仮定

    float crosshairSize = 0.005; // 十字の太さ

    // Y軸 (垂直線) - X座標が中央 (0.5) に近いか
    if (abs(v_TexCoord.x - 0.5) < crosshairSize) {
        fragColor = vec4(1.0, 1.0, 1.0, 1.0); // 色を白 (White) に上書き
    }

    // X軸 (水平線) - Y座標が中央 (0.5) に近いか
    if (abs(v_TexCoord.y - 0.5) < crosshairSize) {
        fragColor = vec4(1.0, 1.0, 1.0, 1.0); // 色を白 (White) に上書き
    }
}