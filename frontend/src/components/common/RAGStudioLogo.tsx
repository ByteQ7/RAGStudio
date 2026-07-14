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
        <linearGradient id="robot-grad" x1="0" y1="0" x2="32" y2="32" gradientUnits="userSpaceOnUse">
          <stop offset="0%" stopColor="#818cf8" />
          <stop offset="100%" stopColor="#6366f1" />
        </linearGradient>
      </defs>

      {/* Robot Head */}
      <rect x="6" y="8" width="20" height="16" rx="4" fill="url(#robot-grad)" opacity="0.95" />

      {/* Antenna */}
      <line x1="16" y1="4" x2="16" y2="8" stroke="url(#robot-grad)" strokeWidth="1.5" strokeLinecap="round" />
      <circle cx="16" cy="3" r="1.8" fill="url(#robot-grad)" />

      {/* Eyes */}
      <rect x="10" y="13" width="4" height="4" rx="1" fill="white" opacity="0.9" />
      <rect x="18" y="13" width="4" height="4" rx="1" fill="white" opacity="0.9" />

      {/* Eye pupils */}
      <circle cx="12" cy="15" r="1" fill="url(#robot-grad)" />
      <circle cx="20" cy="15" r="1" fill="url(#robot-grad)" />

      {/* Mouth */}
      <rect x="13" y="19.5" width="6" height="1.5" rx="0.75" fill="white" opacity="0.7" />

      {/* Ears / Side panels */}
      <rect x="3" y="12" width="3" height="8" rx="1.5" fill="url(#robot-grad)" opacity="0.7" />
      <rect x="26" y="12" width="3" height="8" rx="1.5" fill="url(#robot-grad)" opacity="0.7" />

      {/* Check / accent dots */}
      <circle cx="9" cy="19" r="1" fill="white" opacity="0.15" />
      <circle cx="23" cy="19" r="1" fill="white" opacity="0.15" />
    </svg>
  );
}
