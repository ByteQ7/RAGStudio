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
        "chat-assistant": "hsl(var(--chat-assistant))"
      },
      fontFamily: {
        display: [
          "ui-sans-serif",
          "system-ui",
          "-apple-system",
          "BlinkMacSystemFont",
          '"Segoe UI"',
          "Roboto",
          '"Helvetica Neue"',
          "Arial",
          '"Noto Sans"',
          "sans-serif"
        ],
        body: [
          "ui-sans-serif",
          "system-ui",
          "-apple-system",
          "BlinkMacSystemFont",
          '"Segoe UI"',
          "Roboto",
          '"Helvetica Neue"',
          "Arial",
          '"Noto Sans"',
          "sans-serif"
        ],
        mono: [
          "'JetBrains Mono'",
          "ui-monospace",
          "SFMono-Regular",
          '"SF Mono"',
          "Menlo",
          "Consolas",
          "monospace"
        ]
      },
      boxShadow: {
        soft: "0 1px 3px rgba(15, 23, 42, 0.06)",
        glow: "0 0 0 1px rgba(79, 70, 229, 0.2), 0 4px 16px rgba(79, 70, 229, 0.1)",
        neon: "0 0 24px rgba(79, 70, 229, 0.2)"
      },
      keyframes: {
        "fade-up": {
          "0%": { opacity: 0, transform: "translateY(8px)" },
          "100%": { opacity: 1, transform: "translateY(0)" }
        },
        "pulse-soft": {
          "0%, 100%": { opacity: 1 },
          "50%": { opacity: 0.5 }
        },
        "blink": {
          "0%, 100%": { opacity: 1 },
          "50%": { opacity: 0 }
        },
        "spin-slow": {
          "0%": { transform: "rotate(0deg)" },
          "100%": { transform: "rotate(360deg)" }
        }
      },
      animation: {
        "fade-up": "fade-up 0.3s cubic-bezier(0.25, 1, 0.5, 1)",
        "pulse-soft": "pulse-soft 1.4s ease-in-out infinite",
        "blink": "blink 1s step-end infinite",
        "spin-slow": "spin-slow 4s linear infinite"
      },
      typography: {
        DEFAULT: {
          css: {
            h1: { marginTop: "1rem", marginBottom: "0.75rem" },
            h2: { marginTop: "1rem", marginBottom: "0.75rem" },
            h3: { marginTop: "0.75rem", marginBottom: "0.5rem" },
            h4: { marginTop: "0.75rem", marginBottom: "0.5rem" },
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
