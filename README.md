# ARCore Depth Hazard Detection

ARCore の Depth API と OpenCV を使い、歩行方向にある障害物の距離と下端の高さを推定する Android アプリです。視覚障害者の歩行支援を題材にした卒業研究として開発しました。

> This Android research prototype estimates the distance and bottom height of obstacles using ARCore Depth and OpenCV.

## 背景

駅ホームや通路では、白杖で接触する前に上半身付近の障害物を把握しづらい場合があります。本研究では、深度センサー対応スマートフォンだけで周囲を計測し、進行方向の障害物候補を検出する方法を検討しました。

このリポジトリは、2025年度の卒業研究で作成した最終版アプリを、再現可能なポートフォリオとして整理したものです。研究完成時点の実装を残しながら、現在の知識で段階的に設計・テスト・ドキュメントを改善します。

## 検出イメージ

| 屋内 | 屋外 |
| --- | --- |
| ![屋内で想定する検出範囲](docs/images/indoor-detection-concept.jpg) | ![屋外で想定する検出範囲](docs/images/outdoor-detection-concept.jpg) |

赤色の領域は、端末前方で障害物を検出する範囲の構想図です。掲載画像はアプリ画面のスクリーンショットではなく、研究初期に作成した検出範囲の説明資料です。

## 処理の概要

1. ARCore から16 bit深度画像とカメラ内部パラメータを取得する
2. 画面上部20%の深度中央値を、そのフレームの基準深度として求める
3. 検出領域を左右5%、上20%、下5%を除いた範囲に限定する
4. 深度の二値化と収縮処理を行い、OpenCVで輪郭を抽出する
5. 各候補領域の深度中央値、カメラ高、焦点距離から障害物下端の高さを推定する
6. 検出結果を画面へ描画し、実験用JSONとして端末内に保存する

処理負荷を抑えるため、卒業研究版では10フレームごとにCPU処理を実行します。

## 技術スタック

- Kotlin / Android Views
- ARCore Depth API
- OpenCV 4.12.0
- OpenGL ES 3.0
- Gradle Version Catalog
- JSONによる実験データ保存

## 動作要件

- Android 7.0（API 24）以上
- ARCore対応端末
- Depth API対応端末
- カメラ権限
- JDK 17
- Android SDK 36 / Build Tools 36.0.0

Depth APIの対応状況は端末によって異なります。実機でDepth Modeを利用できない場合、このアプリの主要機能は動作しません。

## ビルド

Android Studioでこのディレクトリを開くか、JDK 17とAndroid SDKを設定して次を実行します。

```powershell
.\gradlew.bat assembleDebug
```

単体テストを含めて確認する場合：

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

## リポジトリの方針

- 卒業研究完成時点の状態は `v0.1.0-graduation-snapshot` として保存予定です。
- 研究資料・実験データ・開発途中の記録は、別のPrivateリポジトリで保管します。
- `main` では機能を保ちながら、責務分割、テスト、UI、アクセシビリティ、性能を改善します。

## 現在の制約

- 研究用プロトタイプであり、安全を保証する製品ではありません。
- 障害物検出結果は照明、反射、端末姿勢、Depth精度の影響を受けます。
- 音声・振動による利用者への通知は未実装です。
- 現在の主要処理は `MainActivity` に集中しており、今後分割予定です。
- 実機による再検証と、対応端末一覧の整理が必要です。

## 注意

本アプリは研究・検証目的です。実際の歩行時の安全装置として単独で使用しないでください。

## Author

[Jr34374](https://github.com/Jr34374)
