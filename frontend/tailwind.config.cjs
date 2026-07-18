/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: ["class"],
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        background: "hsl(var(--background))",
        foreground: "hsl(var(--foreground))",
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
        "chat-user": "hsl(var(--chat-user))",
        "chat-assistant": "hsl(var(--chat-assistant))",
        lobe: {
          50: "#f5f3ff", 100: "#ede9fe", 200: "#ddd6fe",
          300: "#c4b5fd", 400: "#a78bfa", 500: "#8b5cf6",
          600: "#7c3aed", 700: "#6d28d9", 800: "#5b21b6", 900: "#4c1d95"
        }
      },
      fontFamily: {
        display: ["Inter", "ui-sans-serif", "system-ui", "-apple-system", "BlinkMacSystemFont", '"Segoe UI"', "Roboto", '"Helvetica Neue"', "Arial", '"Noto Sans"', "sans-serif"],
        body: ["Inter", "ui-sans-serif", "system-ui", "-apple-system", "BlinkMacSystemFont", '"Segoe UI"', "Roboto", '"Helvetica Neue"', "Arial", '"Noto Sans"', "sans-serif"],
        mono: ["'JetBrains Mono'", "ui-monospace", "SFMono-Regular", '"SF Mono"', "Menlo", "Consolas", "monospace"]
      },
      boxShadow: {
        soft: "0 1px 3px rgba(15, 23, 42, 0.05)",
        glass: "0 0 0 1px rgba(255,255,255,0.1), 0 8px 32px rgba(0,0,0,0.06)",
        "glass-lg": "0 0 0 1px rgba(255,255,255,0.1), 0 16px 48px rgba(0,0,0,0.08)",
        glow: "0 0 0 1px rgba(99,102,241,0.2), 0 4px 16px rgba(99,102,241,0.1)",
        "glow-lg": "0 0 0 1px rgba(99,102,241,0.3), 0 8px 32px rgba(99,102,241,0.15)",
        card: "0 1px 3px rgba(0,0,0,0.04), 0 4px 12px rgba(0,0,0,0.03)",
        cardh: "0 2px 8px rgba(99,102,241,0.08), 0 0 0 1px rgba(99,102,241,0.06)",
        dropdown: "0 4px 16px rgba(0,0,0,0.06), 0 0 0 1px rgba(0,0,0,0.02)",
        modal: "0 20px 60px rgba(0,0,0,0.08), 0 0 0 1px rgba(0,0,0,0.02)"
      },
      backgroundImage: {
        "lobe-gradient": "linear-gradient(135deg, #6366f1 0%, #8b5cf6 50%, #a78bfa 100%)",
        "lobe-subtle": "linear-gradient(135deg, #f5f3ff 0%, #ede9fe 100%)",
        "lobe-card": "linear-gradient(135deg, rgba(255,255,255,0.95) 0%, rgba(255,255,255,0.85) 100%)",
        "lobe-glow": "radial-gradient(ellipse at 50% 0%, rgba(99,102,241,0.08) 0%, transparent 60%)"
      },
      keyframes: {
        "fade-up": { "0%": { opacity: "0", transform: "translateY(8px)" }, "100%": { opacity: "1", transform: "translateY(0)" } },
        "fade-scale": { "0%": { opacity: "0", transform: "scale(0.96)" }, "100%": { opacity: "1", transform: "scale(1)" } },
        "slide-in": { "0%": { opacity: "0", transform: "translateX(-12px)" }, "100%": { opacity: "1", transform: "translateX(0)" } },
        "pulse-soft": { "0%, 100%": { opacity: "1" }, "50%": { opacity: "0.5" } },
        "blink": { "0%, 100%": { opacity: "1" }, "50%": { opacity: "0" } },
        "spin-slow": { "0%": { transform: "rotate(0deg)" }, "100%": { transform: "rotate(360deg)" } },
        shimmer: { "0%": { backgroundPosition: "-200% 0" }, "100%": { backgroundPosition: "200% 0" } },
        "gradient-x": { "0%, 100%": { backgroundPosition: "0% 50%" }, "50%": { backgroundPosition: "100% 50%" } }
      },
      animation: {
        "fade-up": "fade-up 0.3s cubic-bezier(0.25, 1, 0.5, 1)",
        "fade-scale": "fade-scale 0.2s cubic-bezier(0.25, 1, 0.5, 1)",
        "slide-in": "slide-in 0.2s cubic-bezier(0.25, 1, 0.5, 1)",
        "pulse-soft": "pulse-soft 1.4s ease-in-out infinite",
        "blink": "blink 1s step-end infinite",
        "spin-slow": "spin-slow 4s linear infinite",
        shimmer: "shimmer 2s linear infinite",
        "gradient-x": "gradient-x 4s ease infinite"
      },
      typography: {
        DEFAULT: {
          css: {
            maxWidth: "none",
            h1: { marginTop: "1rem", marginBottom: "0.75rem", fontWeight: "600" },
            h2: { marginTop: "1rem", marginBottom: "0.75rem", fontWeight: "600" },
            h3: { marginTop: "0.75rem", marginBottom: "0.5rem", fontWeight: "600" },
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
