; Nova IPTV for Windows — 32-bit installer (Windows 7 SP1 to Windows 11).
; Built by the GitHub workflow:  ISCC.exe /DAppVersion=1.0.N /DSourceDir=... installer.iss

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#ifndef BuildNo
  #define BuildNo "0"
#endif
#ifndef SourceDir
  #define SourceDir "Nova.App\bin\Release\net48"
#endif
; The .NET 4.8 web installer is bundled when the build could download it.
#define HaveNdp FileExists(AddBackslash(SourcePath) + "ndp48-web.exe")

[Setup]
AppId={{6F2C8B54-4E1A-4C7B-9C3D-2A9E5B1F0A11}
AppName=Nova IPTV
AppVersion={#AppVersion}
AppVerName=Nova IPTV 2.0 (build {#BuildNo})
VersionInfoVersion={#AppVersion}
AppPublisher=A.Hamrouni
AppCopyright=Designed by A.Hamrouni
DefaultDirName={autopf}\Nova IPTV
DefaultGroupName=Nova IPTV
DisableProgramGroupPage=yes
OutputBaseFilename=NovaIPTV-Windows-2.0-build-{#BuildNo}-Setup
SetupIconFile=Nova.App\Assets\nova.ico
UninstallDisplayIcon={app}\Nova.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
; 32-bit program: installs in "Program Files (x86)" on 64-bit Windows.
MinVersion=6.1sp1
PrivilegesRequired=admin
CloseApplications=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"
Name: "openwith"; Description: "Add ""Open with Nova"" to the right-click menu of video files (your default player stays)"; GroupDescription: "Right-click menu:"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "*.pdb,*.xml,portable.txt"
#if HaveNdp
Source: "ndp48-web.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall; Check: NeedsDotNet48
#endif

[Registry]
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.mp4\shell\NovaIPTV"; ValueType: string; ValueName: ""; ValueData: "Open with Nova"; Flags: uninsdeletekey; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.mp4\shell\NovaIPTV"; ValueType: string; ValueName: "Icon"; ValueData: """{app}\Nova.exe"",0"; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.mp4\shell\NovaIPTV\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Nova.exe"" ""%1"""; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.mkv\shell\NovaIPTV"; ValueType: string; ValueName: ""; ValueData: "Open with Nova"; Flags: uninsdeletekey; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.mkv\shell\NovaIPTV"; ValueType: string; ValueName: "Icon"; ValueData: """{app}\Nova.exe"",0"; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.mkv\shell\NovaIPTV\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Nova.exe"" ""%1"""; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.avi\shell\NovaIPTV"; ValueType: string; ValueName: ""; ValueData: "Open with Nova"; Flags: uninsdeletekey; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.avi\shell\NovaIPTV"; ValueType: string; ValueName: "Icon"; ValueData: """{app}\Nova.exe"",0"; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.avi\shell\NovaIPTV\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Nova.exe"" ""%1"""; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.ts\shell\NovaIPTV"; ValueType: string; ValueName: ""; ValueData: "Open with Nova"; Flags: uninsdeletekey; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.ts\shell\NovaIPTV"; ValueType: string; ValueName: "Icon"; ValueData: """{app}\Nova.exe"",0"; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.ts\shell\NovaIPTV\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Nova.exe"" ""%1"""; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.m2ts\shell\NovaIPTV"; ValueType: string; ValueName: ""; ValueData: "Open with Nova"; Flags: uninsdeletekey; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.m2ts\shell\NovaIPTV"; ValueType: string; ValueName: "Icon"; ValueData: """{app}\Nova.exe"",0"; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.m2ts\shell\NovaIPTV\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Nova.exe"" ""%1"""; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.mov\shell\NovaIPTV"; ValueType: string; ValueName: ""; ValueData: "Open with Nova"; Flags: uninsdeletekey; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.mov\shell\NovaIPTV"; ValueType: string; ValueName: "Icon"; ValueData: """{app}\Nova.exe"",0"; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.mov\shell\NovaIPTV\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Nova.exe"" ""%1"""; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.wmv\shell\NovaIPTV"; ValueType: string; ValueName: ""; ValueData: "Open with Nova"; Flags: uninsdeletekey; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.wmv\shell\NovaIPTV"; ValueType: string; ValueName: "Icon"; ValueData: """{app}\Nova.exe"",0"; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.wmv\shell\NovaIPTV\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Nova.exe"" ""%1"""; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.flv\shell\NovaIPTV"; ValueType: string; ValueName: ""; ValueData: "Open with Nova"; Flags: uninsdeletekey; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.flv\shell\NovaIPTV"; ValueType: string; ValueName: "Icon"; ValueData: """{app}\Nova.exe"",0"; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.flv\shell\NovaIPTV\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Nova.exe"" ""%1"""; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.webm\shell\NovaIPTV"; ValueType: string; ValueName: ""; ValueData: "Open with Nova"; Flags: uninsdeletekey; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.webm\shell\NovaIPTV"; ValueType: string; ValueName: "Icon"; ValueData: """{app}\Nova.exe"",0"; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.webm\shell\NovaIPTV\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Nova.exe"" ""%1"""; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.m4v\shell\NovaIPTV"; ValueType: string; ValueName: ""; ValueData: "Open with Nova"; Flags: uninsdeletekey; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.m4v\shell\NovaIPTV"; ValueType: string; ValueName: "Icon"; ValueData: """{app}\Nova.exe"",0"; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.m4v\shell\NovaIPTV\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Nova.exe"" ""%1"""; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.mpg\shell\NovaIPTV"; ValueType: string; ValueName: ""; ValueData: "Open with Nova"; Flags: uninsdeletekey; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.mpg\shell\NovaIPTV"; ValueType: string; ValueName: "Icon"; ValueData: """{app}\Nova.exe"",0"; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.mpg\shell\NovaIPTV\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Nova.exe"" ""%1"""; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.mpeg\shell\NovaIPTV"; ValueType: string; ValueName: ""; ValueData: "Open with Nova"; Flags: uninsdeletekey; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.mpeg\shell\NovaIPTV"; ValueType: string; ValueName: "Icon"; ValueData: """{app}\Nova.exe"",0"; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.mpeg\shell\NovaIPTV\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Nova.exe"" ""%1"""; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.m3u\shell\NovaIPTV"; ValueType: string; ValueName: ""; ValueData: "Open with Nova"; Flags: uninsdeletekey; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.m3u\shell\NovaIPTV"; ValueType: string; ValueName: "Icon"; ValueData: """{app}\Nova.exe"",0"; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.m3u\shell\NovaIPTV\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Nova.exe"" ""%1"""; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.m3u8\shell\NovaIPTV"; ValueType: string; ValueName: ""; ValueData: "Open with Nova"; Flags: uninsdeletekey; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.m3u8\shell\NovaIPTV"; ValueType: string; ValueName: "Icon"; ValueData: """{app}\Nova.exe"",0"; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.m3u8\shell\NovaIPTV\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Nova.exe"" ""%1"""; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.3gp\shell\NovaIPTV"; ValueType: string; ValueName: ""; ValueData: "Open with Nova"; Flags: uninsdeletekey; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.3gp\shell\NovaIPTV"; ValueType: string; ValueName: "Icon"; ValueData: """{app}\Nova.exe"",0"; Tasks: openwith
Root: HKLM; Subkey: "Software\Classes\SystemFileAssociations\.3gp\shell\NovaIPTV\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Nova.exe"" ""%1"""; Tasks: openwith

[Icons]
Name: "{group}\Nova IPTV"; Filename: "{app}\Nova.exe"
Name: "{group}\Uninstall Nova IPTV"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Nova IPTV"; Filename: "{app}\Nova.exe"; Tasks: desktopicon

[Run]
#if HaveNdp
Filename: "{tmp}\ndp48-web.exe"; Parameters: "/passive /norestart"; StatusMsg: "Installing Microsoft .NET Framework 4.8 (needs internet)…"; Check: NeedsDotNet48; Flags: waituntilterminated
#endif
Filename: "{app}\Nova.exe"; Description: "Start Nova IPTV"; Flags: nowait postinstall skipifsilent

[Code]
// .NET Framework 4.8 = release number 528040 or higher.
function NeedsDotNet48: Boolean;
var
  release: Cardinal;
begin
  Result := True;
  if RegQueryDWordValue(HKLM, 'SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full', 'Release', release) then
    Result := release < 528040;
end;

// Also removes the per-user "Open with Nova" entries the app itself may have added (Settings › General).
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  exts: TArrayOfString;
  i: Integer;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    SetArrayLength(exts, 15);
    exts[0] := '.mp4';
    exts[1] := '.mkv';
    exts[2] := '.avi';
    exts[3] := '.ts';
    exts[4] := '.m2ts';
    exts[5] := '.mov';
    exts[6] := '.wmv';
    exts[7] := '.flv';
    exts[8] := '.webm';
    exts[9] := '.m4v';
    exts[10] := '.mpg';
    exts[11] := '.mpeg';
    exts[12] := '.m3u';
    exts[13] := '.m3u8';
    exts[14] := '.3gp';
    for i := 0 to GetArrayLength(exts) - 1 do
      RegDeleteKeyIncludingSubkeys(HKCU, 'Software\Classes\SystemFileAssociations\' + exts[i] + '\shell\NovaIPTV');
  end;
end;

function InitializeSetup: Boolean;
begin
  Result := True;
#if !HaveNdp
  if NeedsDotNet48 then
  begin
    MsgBox('Nova IPTV needs Microsoft .NET Framework 4.8.' + #13#10 + #13#10 +
           'Please install it from https://dotnet.microsoft.com/download/dotnet-framework/net48 and run this setup again.',
           mbInformation, MB_OK);
    Result := False;
  end;
#endif
end;
