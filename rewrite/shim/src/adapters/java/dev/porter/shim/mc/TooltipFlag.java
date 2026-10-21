// porter:adapter (generic) - 1.19.2 TooltipFlag (1.7.10 passes a boolean "advanced").
package dev.porter.shim.mc;

public interface TooltipFlag {
    boolean isAdvanced();

    static TooltipFlag of(boolean advanced) { return advanced ? Default.ADVANCED : Default.NORMAL; }

    enum Default implements TooltipFlag {
        NORMAL(false),
        ADVANCED(true);

        private final boolean advanced;

        Default(boolean a) { this.advanced = a; }

        @Override
        public boolean isAdvanced() { return advanced; }
    }
}
