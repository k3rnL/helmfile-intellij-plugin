package dev.helmfile.intellij;

import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

public class HelmfileTypedHandlerTest extends BasePlatformTestCase {
    private boolean oldPairing;
    @Override protected void setUp() throws Exception {
        super.setUp();
        oldPairing = CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET;
        CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET = true;
    }
    @Override protected void tearDown() throws Exception {
        try { CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET = oldPairing; } finally { super.tearDown(); }
    }
    public void testDoubleBraceCreatesBothClosingBracesAndKeepsCaretInside() {
        myFixture.configureByText("helmfile.yaml.gotmpl", "namespace: <caret>\n");
        myFixture.type("{{");
        myFixture.checkResult("namespace: {{<caret>}}\n");
    }
    public void testClosingBracesSkipTheInsertedPair() {
        myFixture.configureByText("helmfile.yaml.gotmpl", "namespace: <caret>\n");
        myFixture.type("{{ .Values.namespace }}");
        myFixture.checkResult("namespace: {{ .Values.namespace }}<caret>\n");
    }
    public void testDoubleBraceInsideQuotedYaml() {
        myFixture.configureByText("helmfile.yaml.gotmpl", "namespace: '<caret>'\n");
        myFixture.type("{{");
        myFixture.checkResult("namespace: '{{<caret>}}'\n");
    }
    public void testDisabledPairingIsRespected() {
        CodeInsightSettings.getInstance().AUTOINSERT_PAIR_BRACKET = false;
        myFixture.configureByText("helmfile.yaml.gotmpl", "namespace: <caret>\n");
        myFixture.type("{{");
        myFixture.checkResult("namespace: {{<caret>\n");
    }
    public void testOrdinaryYamlKeepsItsOwnBraceHandling() {
        myFixture.configureByText("values.yaml", "value: <caret>\n");
        myFixture.type("{");
        myFixture.checkResult("value: {<caret>}\n");
    }
}
