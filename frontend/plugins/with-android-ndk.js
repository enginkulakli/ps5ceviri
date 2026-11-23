const { withProjectBuildGradle } = require("expo/config-plugins");

module.exports = function withAndroidNdk(config) {
  return withProjectBuildGradle(config, (project) => {
    const contents = project.modResults.contents;
    const rootPlugin = 'apply plugin: "expo-root-project"';
    const ndk = 'ext.ndkVersion = "28.2.13676358"';
    if (!contents.includes(ndk)) {
      if (!contents.includes(rootPlugin)) throw new Error("Expo Android root Gradle plugin declaration was not found.");
      project.modResults.contents = contents.replace(rootPlugin, `${ndk}\n${rootPlugin}`);
    }
    return project;
  });
};
