حسب طلبك، سأضيف المزيد من الخانات (أعمدة) إلى الجدول مع أسماء التوزيعات تحت الصور، وأجعل الجدول قابلًا للتمرير (scroll) أفقيًا باستخدام CSS في حال عرضه في بيئة تدعم HTML مثل GitHub أو مواقع الويب. سأضيف توزيعات إضافية (مثل Kali, Mint, Manjaro, CentOS) إلى الجدول.

### الكود المحدث:
```markdown
<div style="overflow-x: auto;">
| <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/ubuntu.png" width="50"><br>Ubuntu | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/arch_linux.png" width="100"><br>Arch | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/fedora.png" width="50"><br>Fedora | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/debian.png" width="50"><br>Debian | <img src="https://upload.wikimedia.org/wikipedia/commons/8/87/Kali_Linux_Logo.png" width="50"><br>Kali | <img src="https://upload.wikimedia.org/wikipedia/commons/3/35/Linux_Mint_logo.svg" width="50"><br>Mint | <img src="https://upload.wikimedia.org/wikipedia/commons/2/2f/Manjaro-logo.svg" width="50"><br>Manjaro | <img src="https://upload.wikimedia.org/wikipedia/commons/6/66/CentOS_logo.png" width="50"><br>CentOS |
|--------|--------|--------|--------|--------|--------|--------|--------|
</div>
```

### الشرح:
1. **الخانات الإضافية**: أضفت أربع توزيعات جديدة (Kali, Mint, Manjaro, CentOS) مع صور شعاراتهم من مصادر ويكيميديا (لأن الصور الأصلية ليست متوفرة في المستودع المذكور).
2. **التمرير الأفقي (Scroll)**: أحطت الجدول بـ `<div style="overflow-x: auto;">` للسماح بالتمرير الأفقي إذا كان عدد الأعمدة كبيرًا جدًا للعرض على الشاشة.
3. **أسماء التوزيعات**: الأسماء موضوعة تحت الصور كما طلبت.
4. **هيكل الجدول**: حافظت على الفواصل (`|--------|`) لضمان تنسيق الجدول بشكل صحيح.

### النتيجة (في بيئة تدعم HTML/Markdown):
<div style="overflow-x: auto;">
| <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/ubuntu.png" width="50"><br>Ubuntu | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/arch_linux.png" width="100"><br>Arch | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/fedora.png" width="50"><br>Fedora | <img src="https://github.com/Magisk-Modules-Alt-Repo/chroot-distro/raw/main/images/debian.png" width="50"><br>Debian | <img src="https://upload.wikimedia.org/wikipedia/commons/8/87/Kali_Linux_Logo.png" width="50"><br>Kali | <img src="https://upload.wikimedia.org/wikipedia/commons/3/35/Linux_Mint_logo.svg" width="50"><br>Mint | <img src="https://upload.wikimedia.org/wikipedia/commons/2/2f/Manjaro-logo.svg" width="50"><br>Manjaro | <img src="https://upload.wikimedia.org/wikipedia/commons/6/66/CentOS_logo.png" width="50"><br>CentOS |
|--------|--------|--------|--------|--------|--------|--------|--------|
</div>

### ملاحظات:
- إذا كنت تستخدم هذا الجدول في بيئة لا تدعم CSS (مثل بعض محررات Markdown الخام)، قد لا يظهر التمرير الأفقي، لكن الجدول سيظل يعمل مع الأعمدة الإضافية.
- إذا كنت تريد إضافة توزيعات أخرى أو تغيير الصور أو تعديل أي شيء، أخبرني!
- إذا كنت تريد الكود بدون `<div>` (لتنسيق خام بدون تمرير)، يمكنني إزالته.

هل هذا ما كنت تقصده؟ أو هل هناك تعديل آخر؟