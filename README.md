# SafeCross AI

تطبيق أندرويد (Kotlin + XML) يستخدم الكاميرا لكشف المركبات على الجهاز وتنبيه المشاة.

- الكشف: MediaPipe + EfficientDet-Lite0 (النموذج في `app/src/main/assets/`)
- الصلاحيات: الكاميرا فقط (+ الاهتزاز وهي صلاحية عادية)
- المعالجة محلية، ولا يُرفع أي فيديو
- منطق الخطر في `risk/RiskEngine.kt` (عتبات تقريبية غير معايرة)

> التطبيق مساعد فقط. عدم ظهور تنبيه لا يعني أن العبور آمن.

البناء: `./gradlew assembleDebug` والناتج في `app/build/outputs/apk/debug/app-debug.apk`
