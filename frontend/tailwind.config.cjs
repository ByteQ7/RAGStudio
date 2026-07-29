/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: ["class"],
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        background: "hsl(var(--background))",
        foreground: "var(--color-text)",
        card: "hsl(var(--card))",
        "card-foreground": "hsl(var(--card-foreground))",
        popover: "hsl(var(--popover))",
        "popover-foreground": "hsl(var(--popover-foreground))",
        primary: "hsl(var(--primary))",
        "primary-foreground": "hsl(var(--primary-foreground))",
        secondary: "hsl(var(--secondary))",
        "secondary-foreground": "hsl(var(--secondary-foreground))",
        muted: "hsl(var(--muted))",
        "muted-foreground": "hsl(var(--muted-foreground))",
        accent: "hsl(var(--accent))",
        "accent-foreground": "hsl(var(--accent-foreground))",
        destructive: "hsl(var(--destructive))",
        "destructive-foreground": "hsl(var(--destructive-foreground))",
        border: "hsl(var(--border))",
        input: "hsl(var(--input))",
        ring: "hsl(var(--ring))",
        "chat-user": "hsl(var(--chat-user-bg))",
        "chat-assistant": "hsl(var(--chat-assistant))"
      },
      fontFamily: {
        display: ["Geist Sans", "-apple-system", "BlinkMacSystemFont", '"Segoe UI Variable Display"', '"Segoe UI"', "Roboto", '"Helvetica Neue"', "Arial", '"HarmonyOS Sans SC"', '"PingFang SC"', '"Hiragino Sans GB"', '"Microsoft YaHei UI"', '"Microsoft YaHei"', "ui-sans-serif", "system-ui", "sans-serif"],
        body: ["Geist Sans", "-apple-system", "BlinkMacSystemFont", '"Segoe UI Variable Display"', '"Segoe UI"', "Roboto", '"Helvetica Neue"', "Arial", '"HarmonyOS Sans SC"', '"PingFang SC"', '"Hiragino Sans GB"', '"Microsoft YaHei UI"', '"Microsoft YaHei"', "ui-sans-serif", "system-ui", "sans-serif"],
        mono: ["Geist Mono", "ui-monospace", "SFMono-Regular", '"SF Mono"', "Menlo", "Consolas", "monospace"]
      },
      fontSize: {
        xs: ["13px", "1.5"],
        sm: ["14px", "1.5"],
        base: ["15px", "1.5714"],
        lg: ["17px", "1.5"],
        xl: ["21px", "1.4"],
        "heading-1": ["38px", "1.2"],
        "heading-2": ["30px", "1.25"],
        "heading-3": ["24px", "1.3"],
        "heading-4": ["20px", "1.35"],
        "heading-5": ["17px", "1.4"]
      },
      spacing: {
        xxs: "4px",
        xs: "8px",
        sm: "12px",
        md: "20px",
        lg: "24px",
        xl: "32px"
      },
      borderRadius: {
        xs: "var(--radius-xs)",
        sm: "var(--radius-sm)",
        DEFAULT: "var(--radius)",
        lg: "var(--radius-lg)"
      },
      boxShadow: {
        tertiary: "var(--shadow-sm)",
        secondary: "var(--shadow-md)",
        DEFAULT: "var(--shadow-lg)"
      },
      keyframes: {
        "fade-up": { "0%": { opacity: "0", transform: "translateY(6px)" }, "100%": { opacity: "1", transform: "translateY(0)" } },
        "fade-scale": { "0%": { opacity: "0", transform: "scale(0.97)" }, "100%": { opacity: "1", transform: "scale(1)" } },
        "slide-in": { "0%": { opacity: "0", transform: "translateX(-10px)" }, "100%": { opacity: "1", transform: "translateX(0)" } },
        "pulse-soft": { "0%, 100%": { opacity: "1" }, "50%": { opacity: "0.5" } },
        "blink": { "0%, 100%": { opacity: "1" }, "50%": { opacity: "0" } },
        "spin-slow": { "0%": { transform: "rotate(0deg)" }, "100%": { transform: "rotate(360deg)" } },
        shimmer: { "0%": { backgroundPosition: "-200% 0" }, "100%": { backgroundPosition: "200% 0" } }
      },
      animation: {
        "fade-up": "fade-up 0.2s cubic-bezier(0.25, 1, 0.5, 1)",
        "fade-scale": "fade-scale 0.15s cubic-bezier(0.25, 1, 0.5, 1)",
        "slide-in": "slide-in 0.15s cubic-bezier(0.25, 1, 0.5, 1)",
        "pulse-soft": "pulse-soft 1.4s ease-in-out infinite",
        "blink": "blink 1s step-end infinite",
        "spin-slow": "spin-slow 4s linear infinite",
        shimmer: "shimmer 2s linear infinite"
      },
      typography: {
        DEFAULT: {
          css: {
            maxWidth: "none",
            color: "var(--color-text)",
            h1: { marginTop: "1rem", marginBottom: "0.75rem", fontWeight: "600", color: "var(--color-text)" },
            h2: { marginTop: "1rem", marginBottom: "0.75rem", fontWeight: "600", color: "var(--color-text)" },
            h3: { marginTop: "0.75rem", marginBottom: "0.5rem", fontWeight: "600", color: "var(--color-text)" },
            h4: { marginTop: "0.75rem", marginBottom: "0.5rem", fontWeight: "600" },
            p: { marginTop: "0.5rem", marginBottom: "0.5rem" },
            ul: { marginTop: "0.5rem", marginBottom: "0.5rem" },
            ol: { marginTop: "0.5rem", marginBottom: "0.5rem" },
            li: { marginTop: "0.25rem", marginBottom: "0.25rem" },
            blockquote: { marginTop: "0.75rem", marginBottom: "0.75rem" },
            table: { marginTop: "0.75rem", marginBottom: "0.75rem" },
            pre: { marginTop: "0.75rem", marginBottom: "0.75rem" },
            code: { fontWeight: "400" },
            "code::before": { content: '""' },
            "code::after": { content: '""' }
          }
        }
      }
    }
  },
  plugins: [require("@tailwindcss/typography")]
};
