; Nova IPTV for Windows — 32-bit installer (Windows 7 SP1 to Windows 11).
; Built by the GitHub workflow:  ISCC.exe /DAppVersion=1.0.N /DSourceDir=... installer.iss

#ifndef AppVersion
  #define AppVersion "1.0.0"
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
AppPublisher=A.H
AppCopyright=Designed by A.H
DefaultDirName={autopf}\Nova IPTV
DefaultGroupName=Nova IPTV
DisableProgramGroupPage=yes
OutputBaseFilename=NovaIPTV-Windows-Setup
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

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "*.pdb,*.xml,portable.txt"
#if HaveNdp
Source: "ndp48-web.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall; Check: NeedsDotNet48
#endif

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
