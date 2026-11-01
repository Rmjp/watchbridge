"""Regenerate the standalone watchOS Xcode project. Python 3, no packages required."""
from pathlib import Path
import hashlib
ROOT=Path(__file__).resolve().parent
def uid(name): return hashlib.sha256(name.encode()).hexdigest()[:24].upper()
sources=sorted((ROOT/'WatchBridge').glob('*.swift'))
project,group,products,target,product,phase,configs,pconfigs=[uid(s) for s in ['project','root','products','target','product','sources','configs','pconfigs']]
asset,resphase,assetbuild=[uid(s) for s in ['assets','resources','assetbuild']]
rows=['// !$*UTF8*$!','{ archiveVersion = 1; classes = {}; objectVersion = 56; objects = {']
for s in sources:
 rows += [f'{uid(s.name)} = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = WatchBridge/{s.name}; sourceTree = "<group>"; }};',f'{uid("build"+s.name)} = {{isa = PBXBuildFile; fileRef = {uid(s.name)}; }};']
rows += [f'{product} = {{isa = PBXFileReference; explicitFileType = wrapper.application; path = WatchBridge.app; sourceTree = BUILT_PRODUCTS_DIR; }};',
 f'{asset} = {{isa = PBXFileReference; lastKnownFileType = folder.assetcatalog; path = WatchBridge/Assets.xcassets; sourceTree = "<group>"; }};',
 f'{assetbuild} = {{isa = PBXBuildFile; fileRef = {asset}; }};',
 f'{resphase} = {{isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = ({assetbuild}); runOnlyForDeploymentPostprocessing = 0; }};',
 f'{group} = {{isa = PBXGroup; children = ({",".join(uid(s.name) for s in sources)},{asset},{products}); sourceTree = "<group>"; }};',
 f'{products} = {{isa = PBXGroup; name = Products; children = ({product}); sourceTree = "<group>"; }};',
 f'{phase} = {{isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = ({",".join(uid("build"+s.name) for s in sources)}); runOnlyForDeploymentPostprocessing = 0; }};',
 f'{target} = {{isa = PBXNativeTarget; buildConfigurationList = {configs}; buildPhases = ({phase},{resphase}); buildRules = (); dependencies = (); name = WatchBridge; productName = WatchBridge; productReference = {product}; productType = "com.apple.product-type.application"; }};',
 f'{project} = {{isa = PBXProject; attributes = {{LastUpgradeCheck = 1600; TargetAttributes = {{{target} = {{CreatedOnToolsVersion = 16.0; }}; }}; }}; buildConfigurationList = {pconfigs}; compatibilityVersion = "Xcode 14.0"; developmentRegion = en; knownRegions = (en,Base); mainGroup = {group}; productRefGroup = {products}; projectDirPath = ""; projectRoot = ""; targets = ({target}); }};']
for name in ['Debug','Release']:
 settings={'SDKROOT':'watchos','SUPPORTED_PLATFORMS':'"watchos watchsimulator"','WATCHOS_DEPLOYMENT_TARGET':'10.0','TARGETED_DEVICE_FAMILY':'4','PRODUCT_BUNDLE_IDENTIFIER':'dev.watchbridge.watch','PRODUCT_NAME':'"$(TARGET_NAME)"','SWIFT_VERSION':'5.0','INFOPLIST_FILE':'WatchBridge/Info.plist','CODE_SIGN_ENTITLEMENTS':'WatchBridge/WatchBridge.entitlements','CODE_SIGN_STYLE':'Automatic','SWIFT_OPTIMIZATION_LEVEL':'"-Onone"' if name=='Debug' else '"-O"','GENERATE_INFOPLIST_FILE':'NO','ENABLE_USER_SCRIPT_SANDBOXING':'YES','SWIFT_STRICT_CONCURRENCY':'minimal'}
 settings['ASSETCATALOG_COMPILER_APPICON_NAME']='AppIcon'
 rows.append(f'{uid("target"+name)} = {{isa = XCBuildConfiguration; buildSettings = {{{" ".join(k+" = "+v+";" for k,v in settings.items())}}}; name = {name}; }};')
 rows.append(f'{uid("project"+name)} = {{isa = XCBuildConfiguration; buildSettings = {{CLANG_ENABLE_MODULES = YES; SWIFT_ACTIVE_COMPILATION_CONDITIONS = {"DEBUG" if name=="Debug" else ""!r}; }}; name = {name}; }};'.replace("'DEBUG'",'DEBUG').replace("''",'""'))
for config,prefix in [(configs,'target'),(pconfigs,'project')]: rows.append(f'{config} = {{isa = XCConfigurationList; buildConfigurations = ({uid(prefix+"Debug")},{uid(prefix+"Release")}); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release; }};')
rows += ['};',f'rootObject = {project};','}']
out=ROOT/'WatchBridge.xcodeproj';out.mkdir(exist_ok=True)
(out/'project.pbxproj').write_text('\n'.join(rows),encoding='utf-8')
schemes=out/'xcshareddata'/'xcschemes';schemes.mkdir(parents=True,exist_ok=True)
reference=f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="WatchBridge.app" BlueprintName="WatchBridge" ReferencedContainer="container:WatchBridge.xcodeproj"/>'
(schemes/'WatchBridge.xcscheme').write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1600" version="1.3">
<BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{reference}</BuildActionEntry></BuildActionEntries></BuildAction>
<LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{reference}</BuildableProductRunnable></LaunchAction>
<ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{reference}</BuildableProductRunnable></ProfileAction>
<AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>''',encoding='utf-8')
print('Generated',out)
