IR Sender - minimal Android app (Kotlin) that sends NEC IR codes from a phone's IR blaster.

BUILD
1. Install Android Studio. File > Open > select this IrSender folder. Let it sync (it downloads Gradle).
2. Connect the phone (USB debugging on) and press Run, or Build > Build APK(s).

USE
- Custom code: 4 hex digits of the remote's NEC custom code (example fb04).
- Keys: one per line, NAME=scancode in hex (example D=0e).
- Macro tokens: NAME  NAME*3  w500 (wait ms)  hold:NAME:3000
- "Scan" sends scancodes 00-FF one by one (1.2 s apart) so you can watch for the projector's power key.

The codes for YOUR remote are not included. They must be captured (IR receiver module) or supplied by the manufacturer.
