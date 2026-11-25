; MRemora Windows installer (NSIS 3.x, Unicode, Polish UI), per-user, no UAC.
; Installs the jpackage app-image (two launchers at the image root: "MRemora Agent.exe" and
; "MRemora Viewer.exe", plus app\ and runtime\, maybe *.dll) into $LOCALAPPDATA\MRemora.
; No user data besides logs ($LOCALAPPDATA\MRemora\logs, written by the apps): an update
; replaces the program files after checking that neither program is running; uninstall
; removes the program files and leaves logs\ in place.
; Build: scripts/build-installer.sh <YYYY.MM.DD.N>
;   makensis -INPUTCHARSET UTF8 -DVERSION=... -DSTAGE=<app-image dir> -DOUTFILE=<exe> -DICON=<ico>

Unicode true

!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "FileFunc.nsh"

!ifndef VERSION
  !error "pass -DVERSION=YYYY.MM.DD.N"
!endif
!ifndef STAGE
  !error "pass -DSTAGE=<absolute path to the app-image dir 'MRemora Agent'>"
!endif
!ifndef OUTFILE
  !error "pass -DOUTFILE=<output exe path>"
!endif
!ifndef ICON
  !error "pass -DICON=<absolute path to packaging/icons/mremora.ico>"
!endif

!define AGENT_EXE "MRemora Agent.exe"
!define VIEWER_EXE "MRemora Viewer.exe"
!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\MRemora"

Name "MRemora"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\MRemora"
; An update into a custom folder lands where the previous version is.
InstallDirRegKey HKCU "${UNINST_KEY}" "InstallLocation"
RequestExecutionLevel user
SetCompressor /SOLID lzma
ShowInstDetails show
ShowUninstDetails show
BrandingText "MRemora ${VERSION}"

VIProductVersion "${VERSION}"
VIAddVersionKey /LANG=1045 "ProductName" "MRemora"
VIAddVersionKey /LANG=1045 "FileVersion" "${VERSION}"
VIAddVersionKey /LANG=1045 "ProductVersion" "${VERSION}"
VIAddVersionKey /LANG=1045 "FileDescription" "Instalator MRemora"
VIAddVersionKey /LANG=1045 "LegalCopyright" ""

!define MUI_ICON "${ICON}"
!define MUI_UNICON "${ICON}"

; ---------- pages ----------

!define MUI_FINISHPAGE_NOAUTOCLOSE
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_FUNCTION LaunchViewer
!define MUI_FINISHPAGE_RUN_TEXT "Uruchom MRemora Viewer"
; LARGE: 60u text box above the checkbox instead of 40u, so the firewall note is not clipped.
!define MUI_FINISHPAGE_TEXT_LARGE
!define MUI_FINISHPAGE_TEXT "Instalacja zakończona. Skróty są na Pulpicie i w menu Start.$\r$\n$\r$\n\
Przy pierwszym uruchomieniu MRemora Agent zezwól na dostęp w Zaporze Windows. \
Jeśli sieć jest oznaczona jako publiczna, zmień ją na prywatną albo zezwól także w sieciach publicznych."

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "Polish"

; ---------- helpers ----------

; Append-open lock test: Windows denies write-open on the image of a running exe.
; "a" mode would CREATE a missing file, hence the exists guard.
!macro CHECK_NOT_RUNNING un
Function ${un}CheckNotRunning
  ; in: exe path on stack; out: "ok" | "cancel" on stack
  Exch $0
  Push $1
  IfFileExists "$0" cnr_retry
    StrCpy $0 "ok"
    Goto cnr_done
  cnr_retry:
  ClearErrors
  FileOpen $1 "$0" a
  IfErrors 0 cnr_ok
  MessageBox MB_RETRYCANCEL|MB_ICONEXCLAMATION \
    "MRemora jest uruchomione (lub plik jest zablokowany):$\r$\n$0$\r$\n$\r$\nZamknij okno MRemora Agent i MRemora Viewer, a następnie kliknij Ponów próbę." \
    /SD IDCANCEL IDRETRY cnr_retry
  StrCpy $0 "cancel"
  Goto cnr_done
  cnr_ok:
  FileClose $1
  StrCpy $0 "ok"
  cnr_done:
  Pop $1
  Exch $0
FunctionEnd
!macroend
!insertmacro CHECK_NOT_RUNNING ""
!insertmacro CHECK_NOT_RUNNING "un."

; Aborts the (un)install section when the given exe is running and the user cancels.
!macro REQUIRE_NOT_RUNNING un exe
  Push "$INSTDIR\${exe}"
  Call ${un}CheckNotRunning
  Pop $0
  ${If} $0 != "ok"
    SetErrorLevel 4
    Abort "Przerwano: MRemora jest uruchomione."
  ${EndIf}
!macroend

; Program files only: logs\ (and anything else the user put there) stays.
!macro REMOVE_PROGRAM_FILES
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  Delete "$INSTDIR\${AGENT_EXE}"
  Delete "$INSTDIR\${VIEWER_EXE}"
  ; jpackage may put the launcher icons next to the exes
  Delete "$INSTDIR\MRemora Agent.ico"
  Delete "$INSTDIR\MRemora Viewer.ico"
  ; jpackage on Windows may copy the MSVC runtime (vcruntime140.dll, msvcp140.dll...) to the image root
  Delete "$INSTDIR\*.dll"
!macroend

Function LaunchViewer
  SetOutPath "$INSTDIR"
  Exec '"$INSTDIR\${VIEWER_EXE}"'
FunctionEnd

; ---------- install ----------

Section "-Install"
  SetDetailsPrint both

  ; -- 1. nothing may run while its files are replaced (checked before any delete)
  !insertmacro REQUIRE_NOT_RUNNING "" "${AGENT_EXE}"
  !insertmacro REQUIRE_NOT_RUNNING "" "${VIEWER_EXE}"

  ; -- 2. update: drop the previous program files, so no stale jar/runtime file survives
  ;    Only when the folder is ours: a chosen folder with an unrelated app\ stays untouched.
  ${If} ${FileExists} "$INSTDIR\${AGENT_EXE}"
  ${OrIf} ${FileExists} "$INSTDIR\uninstall.exe"
    DetailPrint "Usuwanie poprzedniej wersji..."
    !insertmacro REMOVE_PROGRAM_FILES
  ${EndIf}

  ; -- 3. payload
  DetailPrint "Kopiowanie plików programu..."
  SetOutPath "$INSTDIR"
  File /r "${STAGE}\*.*"

  ; -- 4. registration
  DetailPrint "Rejestrowanie instalacji..."
  WriteUninstaller "$INSTDIR\uninstall.exe"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayName" "MRemora"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "Publisher" "MRemora"
  WriteRegStr HKCU "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayIcon" "$INSTDIR\${AGENT_EXE}"
  WriteRegStr HKCU "${UNINST_KEY}" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1
  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  WriteRegDWORD HKCU "${UNINST_KEY}" "EstimatedSize" $0

  ; -- 5. shortcuts (working dir = $INSTDIR via SetOutPath; icon = the exe itself)
  SetOutPath "$INSTDIR"
  CreateShortcut "$DESKTOP\MRemora Agent.lnk" "$INSTDIR\${AGENT_EXE}" "" "$INSTDIR\${AGENT_EXE}" 0
  CreateShortcut "$DESKTOP\MRemora Viewer.lnk" "$INSTDIR\${VIEWER_EXE}" "" "$INSTDIR\${VIEWER_EXE}" 0
  CreateDirectory "$SMPROGRAMS\MRemora"
  CreateShortcut "$SMPROGRAMS\MRemora\MRemora Agent.lnk" "$INSTDIR\${AGENT_EXE}" "" "$INSTDIR\${AGENT_EXE}" 0
  CreateShortcut "$SMPROGRAMS\MRemora\MRemora Viewer.lnk" "$INSTDIR\${VIEWER_EXE}" "" "$INSTDIR\${VIEWER_EXE}" 0
  CreateShortcut "$SMPROGRAMS\MRemora\Odinstaluj MRemora.lnk" "$INSTDIR\uninstall.exe"

  DetailPrint "Gotowe."
SectionEnd

; ---------- uninstall ----------

Section "Uninstall"
  !insertmacro REQUIRE_NOT_RUNNING "un." "${AGENT_EXE}"
  !insertmacro REQUIRE_NOT_RUNNING "un." "${VIEWER_EXE}"

  Delete "$DESKTOP\MRemora Agent.lnk"
  Delete "$DESKTOP\MRemora Viewer.lnk"
  RMDir /r "$SMPROGRAMS\MRemora"
  DeleteRegKey HKCU "${UNINST_KEY}"

  !insertmacro REMOVE_PROGRAM_FILES
  Delete "$INSTDIR\uninstall.exe"
  ; non-recursive: the folder goes only when empty, so logs\ survives
  RMDir "$INSTDIR"
SectionEnd
