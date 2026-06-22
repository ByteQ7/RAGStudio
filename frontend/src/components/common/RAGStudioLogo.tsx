interface RAGStudioLogoProps {
  className?: string;
}

export function RAGStudioLogo({ className }: RAGStudioLogoProps) {
  return (
    <img
      src="/girl.png"
      alt="RAGStudio Logo"
      className={className}
    />
  );
}
