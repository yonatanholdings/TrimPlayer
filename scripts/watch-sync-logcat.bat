@echo off
rem Live logcat filtered to the Garmin watch-sync pipeline, so a failed
rem "watch progress -> phone" pass shows exactly which stage broke:
rem   GarminCompanion     - Connect IQ SDK init / device registration / raw receive
rem   TrimGarminWatchSync - doc received + manifest rebuild
rem   GarminPortcastBridge- remap + import preview/execute counts
rem   PortcastImporter    - parse + conflict detection
rem   PortcastSubscribe/StateWorker - the async apply into the DB
rem   ConnectIQ           - the SDK's own internal logging
rem
rem Usage: connect the phone over USB (USB debugging on), then run this and
rem reproduce (pause on the watch, or use "Get watch progress" in the app).
setlocal
adb wait-for-device
adb logcat -v time ^
  GarminCompanion:V TrimGarminWatchSync:V GarminPortcastBridge:V ^
  PortcastImporter:V PortcastSubscribeWorker:V PortcastStateWorker:V ^
  ConnectIQ:V IQMessageReceiver:V *:S
