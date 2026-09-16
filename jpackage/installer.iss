; Chat2DB Community - Windows installer outer shell (Inno Setup).
;
; Purpose:
;   Produce an outer .exe that, when executed, drops an already-signed .msi
;   to %TEMP% and invokes msiexec on it. Both layers carry Authenticode
;   signatures, so machines with "require signed MSI" Software Restriction
;   Policy (the ones hitting MSI error 1718 / 1625) accept the install.
;
; Division of responsibilities:
;   - MSI  owns everything the installed product needs: files, registry,
;     shortcuts, file associations, ARP entry, upgrade logic via UpgradeCode.
;   - EXE  is a transparent launcher: no uninstall entry, no application
;     state, no legacy cleanup. Anything like "remove old NSIS install"
;     belongs in an MSI Custom Action, not here.
;
; Build invocation (see script/package/wrap_win_installer.sh):
;   ISCC.exe installer.generated.iss
;     # where installer.generated.iss is this file prepended with
;     # #define lines for AppName / AppVersion / MsiFile / OutputDir /
;     # OutputBaseName / IconFile

#ifndef AppName
  #error "AppName must be defined via #define"
#endif
#ifndef AppVersion
  #error "AppVersion must be defined via #define"
#endif
#ifndef MsiFile
  #error "MsiFile (absolute path to the signed .msi) must be defined via #define"
#endif
#ifndef OutputDir
  #define OutputDir "."
#endif
#ifndef OutputBaseName
  #define OutputBaseName "Chat2DB-Pro-setup"
#endif
#ifndef IconFile
  #define IconFile ""
#endif

[Setup]
; AppId is stable across builds so Inno's internal tracking stays consistent.
; It is NOT the MSI's ProductCode — Windows' Add/Remove entry comes from the MSI.
AppId={{BE2D7C86-0852-42CC-AC7E-522B840F958A}
AppName={#AppName}
AppVersion={#AppVersion}
#ifdef AppNativeVersion
VersionInfoVersion={#AppNativeVersion}
#endif
AppPublisher=Aita Technology (Hangzhou) Co., Ltd.
OutputDir={#OutputDir}
OutputBaseFilename={#OutputBaseName}
CreateAppDir=no
Compression=lzma2/ultra
SolidCompression=yes

; The inner MSI is machine-wide so upgrades can safely uninstall previous
; installs from non-system drives. Elevation gives Windows Installer the rights
; it needs for rollback files such as D:\Config.Msi\*.rbf.
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64compatible
ArchitecturesAllowed=x64compatible

; No self-registration. Users see exactly one entry in Apps & Features
; (the one the MSI creates), not two.
Uninstallable=no
CreateUninstallRegKey=no

; Collapse every outer-wrapper page. User sees a brief spinner, then the
; inner MSI owns the actual installation UI, including its directory chooser.
DisableWelcomePage=yes
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=yes
DisableReadyMemo=yes
DisableFinishedPage=yes
DisableStartupPrompt=yes
ShowLanguageDialog=no
WizardStyle=modern

#if IconFile != ""
SetupIconFile={#IconFile}
#endif

[Languages]
; English is always available. Simplified Chinese is enabled when
; script/package/wrap_win_installer.sh detects the language file on the build machine.
#ifdef ChineseMessagesFile
Name: "chinesesimp"; MessagesFile: "{#ChineseMessagesFile}"
#endif
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
; The pre-signed MSI is embedded here. Extracted to %TEMP% at install time
; and deleted when this wrapper exits.
Source: "{#MsiFile}"; DestDir: "{tmp}"; DestName: "chat2db.msi"; Flags: deleteafterinstall ignoreversion

[CustomMessages]
english.LandingPageTitle=Install {#AppName}
english.LandingPageSubtitle=Ready to install.
english.LandingBadge=DESKTOP INSTALLER
english.LandingBody={#AppName} will be installed automatically.
english.LandingFeatureTitle=
english.LandingFeatureList=
english.LandingFooter=
english.InstallNowButton=&Install
english.InstallingPageTitle=Installing {#AppName}
english.InstallingPageSubtitle=Please wait.
english.PreparingStatus=Preparing installation...
english.PreparingDetail=
english.InstallingStatus=Installing {#AppName}. Please wait...
english.InstallingDetail=

#ifdef ChineseMessagesFile
chinesesimp.LandingPageTitle=安装 {#AppName}
chinesesimp.LandingPageSubtitle=已准备好安装。
chinesesimp.LandingBadge=桌面安装程序
chinesesimp.LandingBody={#AppName} 将自动完成安装。
chinesesimp.LandingFeatureTitle=
chinesesimp.LandingFeatureList=
chinesesimp.LandingFooter=
chinesesimp.InstallNowButton=开始安装(&I)
chinesesimp.InstallingPageTitle=正在安装 {#AppName}
chinesesimp.InstallingPageSubtitle=请稍候。
chinesesimp.PreparingStatus=正在准备安装...
chinesesimp.PreparingDetail=
chinesesimp.InstallingStatus=正在安装 {#AppName}，请稍候...
chinesesimp.InstallingDetail=
#endif

[Code]

var
  LandingPage: TWizardPage;
  LandingBadgeLabel: TNewStaticText;
  LandingBodyLabel: TNewStaticText;
  LandingFeatureTitleLabel: TNewStaticText;
  LandingFeatureListLabel: TNewStaticText;
  LandingFooterLabel: TNewStaticText;
  DefaultNextButtonCaption: String;
  StagedMsiPath: String;
  MsiExitCode: Integer;
  FixedInstallDir: Boolean;

procedure BuildLandingPage;
begin
  LandingPage := CreateCustomPage(
    wpWelcome,
    ExpandConstant('{cm:LandingPageTitle}'),
    ExpandConstant('{cm:LandingPageSubtitle}')
  );

  LandingBadgeLabel := TNewStaticText.Create(WizardForm);
  LandingBadgeLabel.Parent := LandingPage.Surface;
  LandingBadgeLabel.Left := 0;
  LandingBadgeLabel.Top := ScaleY(2);
  LandingBadgeLabel.Width := LandingPage.SurfaceWidth;
  LandingBadgeLabel.Height := ScaleY(16);
  LandingBadgeLabel.AutoSize := False;
  LandingBadgeLabel.Caption := ExpandConstant('{cm:LandingBadge}');
  LandingBadgeLabel.Font.Style := [fsBold];
  LandingBadgeLabel.Font.Color := clGrayText;

  LandingBodyLabel := TNewStaticText.Create(WizardForm);
  LandingBodyLabel.Parent := LandingPage.Surface;
  LandingBodyLabel.Left := 0;
  LandingBodyLabel.Top := LandingBadgeLabel.Top + LandingBadgeLabel.Height + ScaleY(12);
  LandingBodyLabel.Width := LandingPage.SurfaceWidth - ScaleX(8);
  LandingBodyLabel.Height := ScaleY(44);
  LandingBodyLabel.AutoSize := False;
  LandingBodyLabel.WordWrap := True;
  LandingBodyLabel.Caption := ExpandConstant('{cm:LandingBody}');

  LandingFeatureTitleLabel := TNewStaticText.Create(WizardForm);
  LandingFeatureTitleLabel.Parent := LandingPage.Surface;
  LandingFeatureTitleLabel.Left := 0;
  LandingFeatureTitleLabel.Top := LandingBodyLabel.Top + LandingBodyLabel.Height + ScaleY(14);
  LandingFeatureTitleLabel.Width := LandingPage.SurfaceWidth;
  LandingFeatureTitleLabel.Height := ScaleY(18);
  LandingFeatureTitleLabel.AutoSize := False;
  LandingFeatureTitleLabel.Caption := ExpandConstant('{cm:LandingFeatureTitle}');
  LandingFeatureTitleLabel.Font.Style := [fsBold];

  LandingFeatureListLabel := TNewStaticText.Create(WizardForm);
  LandingFeatureListLabel.Parent := LandingPage.Surface;
  LandingFeatureListLabel.Left := 0;
  LandingFeatureListLabel.Top := LandingFeatureTitleLabel.Top + LandingFeatureTitleLabel.Height + ScaleY(8);
  LandingFeatureListLabel.Width := LandingPage.SurfaceWidth - ScaleX(8);
  LandingFeatureListLabel.Height := ScaleY(56);
  LandingFeatureListLabel.AutoSize := False;
  LandingFeatureListLabel.WordWrap := True;
  LandingFeatureListLabel.Caption := ExpandConstant('{cm:LandingFeatureList}');

  LandingFooterLabel := TNewStaticText.Create(WizardForm);
  LandingFooterLabel.Parent := LandingPage.Surface;
  LandingFooterLabel.Left := 0;
  LandingFooterLabel.Top := LandingFeatureListLabel.Top + LandingFeatureListLabel.Height + ScaleY(18);
  LandingFooterLabel.Width := LandingPage.SurfaceWidth - ScaleX(8);
  LandingFooterLabel.Height := ScaleY(34);
  LandingFooterLabel.AutoSize := False;
  LandingFooterLabel.WordWrap := True;
  LandingFooterLabel.Caption := ExpandConstant('{cm:LandingFooter}');
  LandingFooterLabel.Font.Color := clGrayText;
end;

procedure InitializeWizard;
begin
  DefaultNextButtonCaption := WizardForm.NextButton.Caption;
  BuildLandingPage;
  WizardForm.FilenameLabel.Visible := False;
end;

procedure HideInstallPathDetails;
begin
  WizardForm.FilenameLabel.Caption := '';
  WizardForm.FilenameLabel.Visible := False;
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if CurPageID = LandingPage.ID then
    WizardForm.NextButton.Caption := ExpandConstant('{cm:InstallNowButton}')
  else
    WizardForm.NextButton.Caption := DefaultNextButtonCaption;

  if CurPageID = wpInstalling then
  begin
    WizardForm.PageNameLabel.Caption := ExpandConstant('{cm:InstallingPageTitle}');
    WizardForm.PageDescriptionLabel.Caption := ExpandConstant('{cm:InstallingPageSubtitle}');
    WizardForm.StatusLabel.Caption := ExpandConstant('{cm:PreparingStatus}');
    HideInstallPathDetails;
    WizardForm.BackButton.Visible := False;
    WizardForm.CancelButton.Enabled := False;
  end
  else
  begin
    WizardForm.BackButton.Visible := True;
    WizardForm.CancelButton.Enabled := True;
  end;
end;

procedure CurInstallProgressChanged(CurProgress, MaxProgress: Integer);
begin
  HideInstallPathDetails;
end;

function GetMsiStageDir: String;
begin
  Result := ExpandConstant('{localappdata}\Programs\{#AppName}\InstallerCache');
end;

function GetMsiStagePath: String;
begin
  Result := AddBackslash(GetMsiStageDir) + '{#AppName}-{#AppVersion}.msi';
end;

function GetMsiLogPath: String;
begin
  Result := AddBackslash(GetMsiStageDir) + '{#AppName}-{#AppVersion}-install.log';
end;

function MsiLogHint(const MsiLogPath: String): String;
begin
  if MsiLogPath = '' then
    Result := ''
  else
    Result := ' MSI log: ' + MsiLogPath;
end;

procedure CleanupStagedMsi;
var
  StageDir: String;
begin
  if (StagedMsiPath <> '') and FileExists(StagedMsiPath) then
    DeleteFile(StagedMsiPath);

  StageDir := GetMsiStageDir;
  if DirExists(StageDir) then
    RemoveDir(StageDir);
end;

procedure PrepareEmbeddedMsiForExecution(var MsiPath: String);
var
  TempMsiPath: String;
  StageDir: String;
begin
  TempMsiPath := ExpandConstant('{tmp}\chat2db.msi');
  if not FileExists(TempMsiPath) then
    RaiseException('Embedded MSI missing at ' + TempMsiPath);

  StageDir := GetMsiStageDir;
  if (not DirExists(StageDir)) and (not ForceDirectories(StageDir)) then
    RaiseException('Failed to create MSI staging directory at ' + StageDir);

  MsiPath := GetMsiStagePath;
  if FileExists(MsiPath) and (not DeleteFile(MsiPath)) then
    RaiseException('Failed to replace existing staged MSI at ' + MsiPath);

  if not CopyFile(TempMsiPath, MsiPath, False) then
    RaiseException('Failed to stage MSI at ' + MsiPath);

  StagedMsiPath := MsiPath;
  Log('Staged embedded MSI to ' + MsiPath);
end;

{ ---------- Run embedded MSI ----------

  Placed in ssPostInstall (after [Files] extraction) rather than [Run] so we
  can inspect msiexec's exit code and surface meaningful failures. }

procedure RunEmbeddedMsi;
var
  MsiPath: String;
  MsiLogPath: String;
  TargetDir: String;
  MsiArguments: String;
  ShowCommand: Integer;
  ResultCode: Integer;
  UpdateInstall: Boolean;
begin
    MsiPath := '';
    MsiLogPath := '';
    TargetDir := ExpandConstant('{param:CHAT2DBTARGETDIR|}');
    FixedInstallDir := ExpandConstant('{param:CHAT2DBFIXEDDIR|0}') = '1';
    UpdateInstall := (ExpandConstant('{param:CHAT2DBUPDATE|0}') = '1') or FixedInstallDir;
    if FixedInstallDir and (TargetDir = '') then
      RaiseException('Fixed update installation requires CHAT2DBTARGETDIR.');
  try
    PrepareEmbeddedMsiForExecution(MsiPath);
    if UpdateInstall then
    begin
      { Update flow logging is owned by UpdateAuditLog. Native UTF-16 MSI
        output must never be mixed into that UTF-8 operation log. }
      MsiLogPath := '';
      Log('MSI verbose logging disabled for updater invocation');
    end
    else
    begin
      MsiLogPath := GetMsiLogPath;
      if FileExists(MsiLogPath) then
        DeleteFile(MsiLogPath);
      Log('MSI install log: ' + MsiLogPath);
    end;

    { Updater launches always pass a target directory. Automatic updates stay
      silent. Fixed fallback updates show basic MSI progress without exposing
      a directory chooser, so the user can complete an update that could not
      be started silently. Standalone installers retain the normal MSI UI. }
    MsiArguments := '/i "' + MsiPath + '" /norestart';
    if MsiLogPath <> '' then
      MsiArguments := MsiArguments + ' /L*v "' + MsiLogPath + '"';
    if TargetDir <> '' then
      MsiArguments := MsiArguments + ' INSTALLDIR="' + TargetDir + '"';
    ShowCommand := SW_SHOW;
    if UpdateInstall then
    begin
      if FixedInstallDir then
      begin
        MsiArguments := MsiArguments + ' /qb';
        ShowCommand := SW_SHOW;
        Log('Running embedded MSI in fixed-directory update mode')
      end
      else
      begin
        MsiArguments := MsiArguments + ' /qn';
        ShowCommand := SW_HIDE;
        Log('Running embedded MSI in automatic update mode');
      end;
    end;
    WizardForm.StatusLabel.Caption := ExpandConstant('{cm:InstallingStatus}');
    HideInstallPathDetails;
    WizardForm.CancelButton.Enabled := False;
    if not Exec('msiexec.exe',
                MsiArguments,
                '',
                ShowCommand,
                ewWaitUntilTerminated,
                ResultCode) then
    begin
      MsiExitCode := 1;
      RaiseException('Failed to launch msiexec (OS error ' + IntToStr(ResultCode) + ')');
    end;

    MsiExitCode := ResultCode;

    { msiexec exit codes we treat as success:
        0    - success
        3010 - success, reboot required later
      Special cases surfaced with friendlier message:
        1602 - user cancelled
        1638 - same/another product version is already installed
        1625 - blocked by digital signature policy (should NOT happen now that
               the MSI is signed — if it does, the remote signing step failed) }
    case ResultCode of
      0, 3010: ;
      1602:
        RaiseException('Installation cancelled.');
      1625:
        RaiseException('Installation blocked by system policy (1625). ' +
          'The embedded MSI signature did not pass; contact the vendor.' +
          MsiLogHint(MsiLogPath));
      1638:
        RaiseException('Another version of {#AppName} is already installed (1638). ' +
          'Install a newer package version, or uninstall the existing version first.' +
          MsiLogHint(MsiLogPath));
    else
      RaiseException('MSI install failed with exit code ' + IntToStr(ResultCode) + '.' +
        MsiLogHint(MsiLogPath));
    end;
  finally
    CleanupStagedMsi;
  end;
end;

function GetCustomSetupExitCode: Integer;
begin
  Result := MsiExitCode;
end;

procedure DeinitializeSetup;
begin
  CleanupStagedMsi;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
    RunEmbeddedMsi;
end;
