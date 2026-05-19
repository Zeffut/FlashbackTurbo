#!/usr/bin/env bash
# Génère et build la variante 26.1.x du mod depuis la source 1.21.x principale.
# Évite le drift manuel entre les deux branches : on adapte mécaniquement les
# noms yarn → mojang et on utilise un build.gradle + gradle.properties spécifique.
#
# Usage : ./scripts/build-26.1.sh
# Output : /tmp/FlashbackTurbo-26.1/build/libs/flashbackturbo-X.Y.Z.jar

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FORK="/tmp/FlashbackTurbo-26.1"
JAVA25=/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home

echo "[26.1] reset fork dir → $FORK"
rm -rf "$FORK"
cp -r "$ROOT" "$FORK"
cd "$FORK"
rm -rf .gradle build run

# Replace 1.21.x flashback jar with 26.1 jar
rm -f libs/Flashback-0.39.5-for-MC1.21.11.jar libs/Flashback-0.39.5-for-MC1.21.10.jar 2>/dev/null
if [[ ! -f libs/Flashback-0.40.0-for-MC26.1.jar ]]; then
    echo "[26.1] downloading Flashback 0.40.0..."
    curl -sL -o libs/Flashback-0.40.0-for-MC26.1.jar \
        "https://cdn.modrinth.com/data/4das1Fjq/versions/XRtfepgi/Flashback-0.40.0-for-MC26.1.jar"
fi

echo "[26.1] remapping yarn → mojang in source"
find src/main/java -name "*.java" -print0 | xargs -0 sed -i '' \
    -e 's|net.minecraft.client.texture.NativeImage|com.mojang.blaze3d.platform.NativeImage|g' \
    -e 's|net.minecraft.util.math.Vec3d|net.minecraft.world.phys.Vec3|g' \
    -e 's|net.minecraft.client.MinecraftClient|net.minecraft.client.Minecraft|g' \
    -e 's|MinecraftClient\.|Minecraft.|g' \
    -e 's|MinecraftClient mc|Minecraft mc|g' \
    -e 's|MinecraftClient |Minecraft |g' \
    -e 's|net.minecraft.text.Text|net.minecraft.network.chat.Component|g' \
    -e 's|Text\.literal|Component.literal|g' \
    -e 's|net.minecraft.client.gui.screen.Screen|net.minecraft.client.gui.screens.Screen|g' \
    -e 's|net.minecraft.client.gui.DrawContext|net.minecraft.client.gui.GuiGraphicsExtractor|g' \
    -e 's|DrawContext |GuiGraphicsExtractor |g' \
    -e 's|DrawContext)|GuiGraphicsExtractor)|g' \
    -e 's|DrawContext,|GuiGraphicsExtractor,|g' \
    -e 's|getRenderTickCounter()|getDeltaTracker()|g' \
    -e 's|mc\.currentScreen|mc.screen|g' \
    -e 's|getWindow()\.getHandle()|getWindow().handle()|g' \
    -e 's|drawCenteredTextWithShadow|centeredText|g' \
    -e 's|this\.textRenderer|this.font|g' \
    -e 's|shouldPause()|isPauseScreen()|g'

# FastPngWriter : copyPixelsArgb() n'existe pas en mojang, utiliser getPixelsABGR + convert
python3 <<'PY'
p='/tmp/FlashbackTurbo-26.1/src/main/java/fr/zeffut/flashbackturbo/png/FastPngWriter.java'
with open(p) as f: s=f.read()
s = s.replace('image.getFormat()', 'image.format()')
s = s.replace('int[] argb = image.copyPixelsArgb();', '''int[] abgr = image.getPixelsABGR();
        int[] argb = new int[abgr.length];
        for (int i = 0; i < abgr.length; i++) {
            int v = abgr[i];
            argb[i] = (v & 0xFF00FF00) | ((v & 0xFF) << 16) | ((v >>> 16) & 0xFF);
        }''')
open(p,'w').write(s)
PY

# SavingExportScreen : render(GuiGraphicsExtractor) → extractRenderState(GuiGraphicsExtractor)
sed -i '' 's|public void render(GuiGraphicsExtractor|public void extractRenderState(GuiGraphicsExtractor|g' \
    src/main/java/fr/zeffut/flashbackturbo/gui/SavingExportScreen.java 2>/dev/null || true

# build.gradle pour Loom 1.15-SNAPSHOT + Java 25 + pas de mappings dep
cat > build.gradle <<'GRADLE'
buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven { url 'https://jitpack.io' }
        maven { url "https://plugins.gradle.org/m2/" }
    }
}

plugins {
    id 'net.fabricmc.fabric-loom' version '1.15-SNAPSHOT'
    id 'maven-publish'
}

version = project.mod_version
group = project.maven_group

base { archivesName = project.archives_base_name }

repositories {
    maven { name = 'Modrinth'; url = 'https://api.modrinth.com/maven'; content { includeGroup 'maven.modrinth' } }
    mavenLocal()
}

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    implementation "net.fabricmc:fabric-loader:${project.loader_version}"
    implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"
    def fb = file("libs/${project.flashback_jar}")
    if (fb.exists()) { compileOnly files(fb) }
}

processResources {
    inputs.property 'version', project.version
    filesMatching('fabric.mod.json') { expand 'version': project.version }
}

tasks.withType(JavaCompile).configureEach { it.options.release = 25 }

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

jar { from('LICENSE') { rename { "${it}_${project.archives_base_name}" } } }
GRADLE

# gradle.properties pour 26.1.2
MOD_VERSION=$(grep "^mod_version=" "$ROOT/gradle.properties" | cut -d= -f2)
cat > gradle.properties <<EOF
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true

minecraft_version=26.1.2
loader_version=0.19.2

mod_version=$MOD_VERSION
maven_group=fr.zeffut
archives_base_name=flashbackturbo

fabric_version=0.149.1+26.1.2
flashback_version=0.40.0
flashback_jar=Flashback-0.40.0-for-MC26.1.jar
EOF

# fabric.mod.json : minecraft ~26.1, java >=25
python3 <<'PY'
import json
p='src/main/resources/fabric.mod.json'
with open(p) as f: d=json.load(f)
d['depends']['minecraft'] = '~26.1'
d['depends']['java'] = '>=25'
with open(p,'w') as f: json.dump(d, f, indent=2)
PY

echo "[26.1] building (Loom 1.15-SNAPSHOT, JDK 25)"
JAVA_HOME=$JAVA25 ./gradlew clean build --no-daemon 2>&1 | tail -10

JAR=$(ls -t build/libs/flashbackturbo-*.jar 2>/dev/null | grep -v sources | head -1)
if [[ -n "$JAR" && -f "$JAR" ]]; then
    echo ""
    echo "[26.1] ✅ build OK → $JAR"
    ls -la "$JAR"
else
    echo ""
    echo "[26.1] ❌ build failed"
    exit 1
fi
