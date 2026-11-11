import org.jspecify.annotations.NullMarked;

/**
 * @author VISTALL
 * @since 2026-10-13
 */
@NullMarked
module consulo.claude {
    requires consulo.ai.api;
    requires consulo.account.management.api;
    requires consulo.process.api;
    requires consulo.ui.api;
    requires com.google.gson;
}
