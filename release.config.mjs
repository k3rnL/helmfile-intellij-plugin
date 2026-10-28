export default {
  branches: ["main"],
  repositoryUrl: "https://github.com/k3rnL/helmfile-intellij-plugin.git",
  tagFormat: "v${version}",
  plugins: [
    ["@semantic-release/commit-analyzer", { preset: "conventionalcommits" }],
    ["@semantic-release/release-notes-generator", { preset: "conventionalcommits" }],
    "./scripts/marketplace-release.mjs",
    ["@semantic-release/github", {
      assets: [{ path: "build/distributions/*.zip", label: "Helmfile IntelliJ plugin" }],
      successCommentCondition: false,
      failCommentCondition: false,
      releasedLabels: false,
    }],
  ],
};
