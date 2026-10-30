package dev.helmfile.intellij;

/** File-wide diff, available outside any individual release declaration. */
public final class HelmfileDiffAction extends HelmfileStateAction {
    public HelmfileDiffAction() { super(HelmfileOperation.DIFF); }
}
