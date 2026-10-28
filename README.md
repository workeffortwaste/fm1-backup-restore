# FM-1 Backup & Restore *for Android*

_Back up and restore your M-VAVE FM-1 from an Android phone._

Back up your M-VAVE FM-1 and restore it whenever you need to from your Android phone. Each backup is a complete flash snapshot you can return to at any point; the **_firmware_**, **_settings_**, **_projects_**, **_songs_**, and other data, stored exactly as they were when you saved it. Restore puts that data back byte for byte, leaving the protected bootloader completely untouched.

> **Support this project** <br/> Help support the work that goes into creating and maintaining my projects and sponsor me via [GitHub Sponsors](https://github.com/sponsors/workeffortwaste/).

## Download

The latest version can be downloaded from the link below or from the releases section on the repo.

[Download the latest APK](https://github.com/workeffortwaste/fm1-backup-restore/releases/latest/download/fm1-backup-restore.apk)

## Screenshots

<p>
  <img width="300" alt="custom-black-connected" src="https://github.com/user-attachments/assets/60abc3fd-457b-4433-8a21-86209b7dad9b" alt="Backup library in the Black theme"/>
  <img width="300" alt="custom-black-backup-75" src="https://github.com/user-attachments/assets/0e575c8f-7afc-4d1a-bbfb-070ff5fa763f" alt="Backup progress in the Black theme"/>
</p>

## Use cases

#### Keep a factory-fresh backup

Keep a full-flash snapshot from when your FM-1 first arrives, so you can always return to a byte for byte factory state at the tap of a button.

#### Save songs and projects

Back up your songs, projects and patches together in one complete flash snapshot and return to it any any time.

#### Disaster recovery

Restore a known-good backup if an experiment or a change leaves the FM-1 in a state you don't want.

#### Firmware hopping

Switch between saved firmware backups, or restore your own clean base before installing new firmware, without worrying about settings or data from the last firmware being strewn across your FM-1.

#### Tinkering with your FM-1

You just like playing with the new shiny thing for your FM-1

## Use at your own risk

Flashing or restoring your device carries an inherent risk of data loss or leaving it unusable. I've tested this tool over 100 times with writes and restores across different firmwares and versions, but I can't guarantee it's free of issues or will work with every phone.

This tool is provided as-is. Use it at your own risk, and I accept no responsibility for damage, data loss or other problems caused by its use.

## Using the app

> **Requirements** <br/>You'll need Android 8 or later and a data-capable USB OTG cable (OTG?! If you've got a USBC cable that works with data, and a phone with a USBC port, you're good).

First, a bit of house-keeping; when you first launch the app it will require you to turn off background optimisation for the app on your phone, this is labelled as allowing background transfers. This is critical as it means the app can continue a backup or restore while the phone's screen is off, or if you switch apps.

It won't let you continue unless you toggle the setting.

### Making a backup

Connect your FM-1 and tap **Connect to FM-1**. The app will restart it into recovery mode and ask for USB access. Once it's ready, tap **Backup FM-1**.

The app reads the full 1 MiB flash twice and checks the saved copy before adding it to your backup library. You can rename backups, pin the ones you want to keep close, and export a copy through the menu.

**Keep an exported copy somewhere safe.** Backups stored only inside the app data for convenience and are lost if you uninstall it or clear its data. If you want to keep a copy somewhere else export it via the settings in the app.

### Restoring

Choose **Restore** from a saved backup's menu, or **Restore from file** in the top menu to use an pre-existing  `.bin` backup.

Read the confirmation carefully: restoring replaces the FM-1's firmware, settings and patches (everything) outside the protected bootloader region.

Keep the USB cable and power connected until the restore has finished and been verified. Restores cannot be cancelled once started, and an interrupted restore can leave the FM-1 unable to boot normally.

## Themes

The app is built with **Material 3 Expressive**, with support for your Material You dynamic colors alongside the built-in FM-1 styled themes.

### FM-1 Themes

Want a fixed look to match your synth? Choose **FM-1 Themes** in _Settings_ and pick one of the built-in FM-1 styled palettes. The app uses the Black palette by default.

Choose **App icon** in _Settings_ to pick an icon independently of your theme. *Applying an icon closes the app*.

<table>
  <tr>
    <td align="center"><img src="https://github.com/user-attachments/assets/57f9b99a-5115-4c2d-a521-c8830c820007" width="180" alt="Black theme"><br>Black</td>
    <td align="center"><img src="https://github.com/user-attachments/assets/e1517992-1883-450a-86d7-6e127451c5e6" width="180" alt="Orange theme"><br>Orange</td>
    <td align="center"><img src="https://github.com/user-attachments/assets/779ac6de-17e2-48b8-b1a9-a0658a765b3b" width="180" alt="Purple theme"><br>Purple</td>
    <td align="center"><img src="https://github.com/user-attachments/assets/aed34524-89a9-4f18-8275-1f5f4a95f1cd" width="180" alt="Green theme"><br>Green</td>
    <td align="center"><img src="https://github.com/user-attachments/assets/b4ba2466-d18b-4bab-ac8d-877d5e501b10" width="180" alt="Blue theme"><br>Blue</td>
  </tr>
</table>

Yes, orange is pretty ick, but so's the device colour - sorry.

### Material You

Choose **Material You** in Settings to use your device's colors throughout the app.

<table>
  <tr>
    <td align="center"><img src="https://github.com/user-attachments/assets/56409052-cda9-4f34-8e9d-e92ca3f07786" width="180" alt="Material You theme"><br>Material You</td>
  </tr>
</table>

## App updates and privacy

The app checks the latest stable GitHub Release when opened, at most once every 24 hours. You can also check manually in **Settings > App updates**. Update checks contact GitHub directly and there's no additional third party tracking or analytics within the app.

## What next?

What would you like to see next? More themes? Firmware flashing? Hassle me on social media or [open an issue](https://github.com/workeffortwaste/fm1-backup-restore/issues).


## Troubleshooting

### FM-1 not recognised

Don't worry if the app doesn't recognise your FM-1 when you plug it into your phone. You might be using firmware that changes its USB vendor and product IDs (VID/PID) to a combination I haven't accounted for yet.

[Get in touch](https://bsky.app/profile/defaced.dev) or [open an issue](https://github.com/workeffortwaste/fm1-backup-restore/issues) and let me know which firmware you're using. I'll help get it recognised.

## Sponsors

If you find the app useful, please consider supporting the work that goes into creating and maintaining my projects via [GitHub Sponsors](https://github.com/sponsors/workeffortwaste/) or [buy me a coffee on Ko-fi](https://ko-fi.com/defaced).

## Author

Chris Johnson - [defaced.dev](https://defaced.dev) - [@defaced.dev](https://bsky.app/profile/defaced.dev) (Bluesky)

What would you like to see next? More themes? Firmware flashing? Hassle me on social media or [open an issue](https://github.com/workeffortwaste/fm1-backup-restore/issues).
