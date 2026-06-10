interface RAGStudioLogoProps {
  className?: string;
}

export function RAGStudioLogo({ className }: RAGStudioLogoProps) {
  return (
    <svg
      viewBox="0 0 32 32"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      className={className}
    >
      <defs>
        <linearGradient id="ai-grad" x1="0" y1="0" x2="32" y2="32" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stopColor="#818cf8" />
          <stop offset="100%" stopColor="#6366f1" />
        </linearGradient>
        <linearGradient id="ai-grad-light" x1="0" y1="0" x2="32" y2="32" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stopColor="#c7d2fe" />
          <stop offset="100%" stopColor="#a5b4fc" />
        </linearGradient>
      </defs>

      {/* Neural network nodes — background connections */}
      <circle cx="16" cy="16" r="10" stroke="url(#ai-grad-light)" strokeWidth="0.8" opacity="0.35" />
      <circle cx="16" cy="16" r="5.5" stroke="url(#ai-grad-light)" strokeWidth="0.6" opacity="0.25" fill="none" />

      {/* Connection lines — neural links */}
      <line x1="8" y1="10" x2="13" y2="14.5" stroke="url(#ai-grad-light)" strokeWidth="0.8" opacity="0.5" />
      <line x1="24" y1="10" x2="19" y2="14.5" stroke="url(#ai-grad-light)" strokeWidth="0.8" opacity="0.5" />
      <line x1="8" y1="22" x2="13" y2="17.5" stroke="url(#ai-grad-light)" strokeWidth="0.8" opacity="0.5" />
      <line x1="24" y1="22" x2="19" y2="17.5" stroke="url(#ai-grad-light)" strokeWidth="0.8" opacity="0.5" />
      <line x1="13" y1="14.5" x2="19" y2="14.5" stroke="url(#ai-grad-light)" strokeWidth="0.6" opacity="0.3" />
      <line x1="13" y1="17.5" x2="19" y2="17.5" stroke="url(#ai-grad-light)" strokeWidth="0.6" opacity="0.3" />
      <line x1="16" y1="6" x2="16" y2="11" stroke="url(#ai-grad-light)" strokeWidth="0.6" opacity="0.3" />
      <line x1="16" y1="21" x2="16" y2="26" stroke="url(#ai-grad-light)" strokeWidth="0.6" opacity="0.3" />

      {/* Peripheral nodes */}
      <circle cx="8" cy="10" r="1.8" fill="url(#ai-grad-light)" opacity="0.6" />
      <circle cx="24" cy="10" r="1.8" fill="url(#ai-grad-light)" opacity="0.6" />
      <circle cx="8" cy="22" r="1.8" fill="url(#ai-grad-light)" opacity="0.6" />
      <circle cx="24" cy="22" r="1.8" fill="url(#ai-grad-light)" opacity="0.6" />
      <circle cx="16" cy="6" r="1.5" fill="url(#ai-grad-light)" opacity="0.4" />
      <circle cx="16" cy="26" r="1.5" fill="url(#ai-grad-light)" opacity="0.4" />
      <circle cx="13" cy="14.5" r="1.2" fill="url(#ai-grad-light)" opacity="0.35" />
      <circle cx="19" cy="14.5" r="1.2" fill="url(#ai-grad-light)" opacity="0.35" />
      <circle cx="13" cy="17.5" r="1.2" fill="url(#ai-grad-light)" opacity="0.35" />
      <circle cx="19" cy="17.5" r="1.2" fill="url(#ai-grad-light)" opacity="0.35" />

      {/* Center brain core */}
      <circle cx="16" cy="16" r="4.5" fill="url(#ai-grad)" opacity="0.95" />

      {/* Central node glow */}
      <circle
        cx="16" cy="16" r="2.5"
        fill="white"
        opacity="0.2"
        style={{ animation: 'ai-pulse 3s ease-in-out infinite' }}
      />

      {/* Inner spark / synapse */}
      <circle cx="16" cy="16" r="1.2" fill="white" opacity="0.9" />

      {/* Spark dots on center - small bright accents */}
      <circle cx="14.5" cy="14.5" r="0.6" fill="white" opacity="0.6" />
      <circle cx="17.5" cy="17.5" r="0.6" fill="white" opacity="0.6" />
      <circle cx="17.5" cy="14.5" r="0.5" fill="white" opacity="0.4" />
      <circle cx="14.5" cy="17.5" r="0.5" fill="white" opacity="0.4" />

      <style>{`
        @keyframes ai-pulse {
          0%, 100% { transform: scale(1); opacity: 0.2; }
          50% { transform: scale(1.4); opacity: 0.35; }
        }
      `}</style>
    </svg>
  );
}
