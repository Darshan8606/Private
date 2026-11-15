# Reel Counter: install guide

## Step 1: Get the APK (about 5 minutes, no coding)
The APK has to be compiled by a build tool. The project does this for you on GitHub, free:

1. Create a free account at github.com and click **New repository** (any name, Private is fine).
2. On the repository page click **uploading an existing file**, then drag in everything from the
   unzipped ReelCounter folder (app, .github, build.gradle.kts, gradle.properties, settings.gradle.kts).
   If the hidden `.github` folder is not accepted: click **Add file > Create new file**, type the name
   `.github/workflows/build.yml`, and paste in the contents of that file.
3. Click **Commit changes**. Open the **Actions** tab. A run called "Build APK" starts (about 3-5 minutes).
4. When it shows a green tick, open it and download **ReelCounter-APK** at the bottom. Unzip it: that is `app-debug.apk`.

(Alternative: install Android Studio, open the folder, choose Build > Build APK(s).)

## Step 2: Install
1. Send app-debug.apk to your phone (USB, Drive, email).
2. Tap it. If asked, allow "Install unknown apps" for the app you opened it from.

## Step 3: Turn it on
1. Open Reel Counter and switch on **Enable Counter**.
2. Tap **Open Settings**, open Reel Counter in the Accessibility list, turn on **Use Reel Counter**, confirm.
3. If Android says "Restricted setting" (Android 13+ for side-loaded apps): Settings > Apps > Reel Counter >
   menu (top right) > **Allow restricted settings**, then repeat step 2.
4. Optional but recommended: Settings > Battery > Reel Counter > Unrestricted, so Android does not stop the service.

Open Instagram > Reels. Swipe to the next reel: ▶ 1 appears top right.

## How counting works, and its limits
- The first reel you land on is not counted; each move to a *new* reel is +1.
- Likes, shares, pausing, comments (open/close/scroll) and the home feed do not count.
- Scrolling back to a reel you already counted and then forward again does not count twice.
- The count is saved with the date and reset by date comparison, so midnight reset needs no running app.
- Detection reads Instagram's on-screen accessibility labels (author/caption), which Instagram can rename in any
  update. It was written without access to a phone, so it is untested. If the counter shows but never goes
  up (or counts too much), Instagram changed its layout and the ids in ReelAccessibilityService.kt need adjusting.
- Privacy: no internet permission, nothing is stored but the date and the number.
