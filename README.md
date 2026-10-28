# محافظي

تطبيق أندرويد بيقرا رسايل **فودافون كاش** و**اتصالات كاش** وينظمها لوحده: الوارد والصادر والرسوم والرصيد، وحدود الشهر واليوم، وينبّه قبل ما الفلوس تتقفل للشهر الجاي.

## التنزيل

آخر نسخة دايماً هنا:
https://github.com/mostafasukar33/flashon-wallet/releases/latest/download/flashon-wallet.apk

بعد أول تسطيب، التحديثات بتظهر جوه التطبيق نفسه.

## الخصوصية

الرسايل والأرقام والفلوس كلها بتفضل جوه الموبايل. التطبيق مش بيبعت أي بيانات لأي حتة. الإنترنت بيتستخدم بس عشان يشوف فيه نسخة جديدة.

## للمطور

- `app/src/main/java/com/flashon/wallet/core/` قراية الرسايل وحسبة الحدود (Kotlin من غير أندرويد).
- `coretest/Test.kt` تجارب على رسايل حقيقية:
  `kotlinc app/src/main/java/com/flashon/wallet/core/*.kt coretest/Test.kt -include-runtime -d t.jar && java -jar t.jar`
- `app/src/main/assets/web/index.html` الشاشات.
- كل push على `main` بيبني APK موقّع وينزله كـ release اسمه `v<رقم البناء>`.
- مفتاح التوقيع في `signing/` لازم يفضل هو هو، وإلا التحديثات مش هتتسطب فوق النسخة القديمة.
