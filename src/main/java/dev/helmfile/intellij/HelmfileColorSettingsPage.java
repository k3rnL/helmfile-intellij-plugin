package dev.helmfile.intellij;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.*;
import java.util.Map;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;

public final class HelmfileColorSettingsPage implements ColorSettingsPage {
    @Override public Icon getIcon() { return HelmfileFileType.INSTANCE.getIcon(); }
    @Override public @NotNull SyntaxHighlighter getHighlighter() { return new HelmfileSyntaxHighlighterFactory().getSyntaxHighlighter(null, null); }
    @Override public @NotNull String getDisplayName() { return "Helmfile"; }
    @Override public AttributesDescriptor @NotNull [] getAttributeDescriptors() {
        return new AttributesDescriptor[]{
                new AttributesDescriptor("Template delimiters", HelmfileColors.DELIMITERS),
                new AttributesDescriptor("Control keywords", HelmfileColors.KEYWORD),
                new AttributesDescriptor("Go built-in functions", HelmfileColors.GO_FUNCTION),
                new AttributesDescriptor("Sprig functions", HelmfileColors.SPRIG_FUNCTION),
                new AttributesDescriptor("Helmfile functions", HelmfileColors.HELMFILE_FUNCTION),
                new AttributesDescriptor("Context objects (.Values, .StateValues, .Release)", HelmfileColors.CONTEXT),
                new AttributesDescriptor("Values and properties", HelmfileColors.PROPERTY),
                new AttributesDescriptor("Local variables", HelmfileColors.VARIABLE),
                new AttributesDescriptor("Strings", HelmfileColors.STRING),
                new AttributesDescriptor("Numbers", HelmfileColors.NUMBER),
                new AttributesDescriptor("Boolean and nil literals", HelmfileColors.CONSTANT),
                new AttributesDescriptor("Operators and punctuation", HelmfileColors.OPERATOR),
                new AttributesDescriptor("Comments", HelmfileColors.COMMENT)};
    }
    @Override public ColorDescriptor @NotNull [] getColorDescriptors() { return ColorDescriptor.EMPTY_ARRAY; }
    @Override public Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() { return null; }
    @Override public @NotNull String getDemoText() {
        return """
                environments:
                  production:
                    values:
                      - namespace: production
                      - enabled: true
                ---
                {{/* Go control flow, Helmfile functions, and Sprig pipelines */}}
                {{- if .StateValues.enabled }}
                {{ $name := printf "%s-api" .Environment.Name }}
                releases:
                  - name: {{ $name | lower }}
                    namespace: {{ .StateValues.namespace | quote }}
                    version: {{ requiredEnv "APP_VERSION" | default "1.0" }}
                    installed: {{ true }}
                    values:
                      - replicas: {{ 3 }}
                      - config: {{ readFile "config.yaml" | fromYaml | toYaml }}
                    # Escaped templates are highlighted for the later rendering pass too.
                    chart: '{{`{{ printf "%s-chart" .Release.Name | lower }}`}}'
                {{- end }}
                """;
    }
}
