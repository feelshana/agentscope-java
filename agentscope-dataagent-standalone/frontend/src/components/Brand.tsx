import mark from '../assets/brand-mark.svg';
import './Brand.css';

/** The same vector is also used by the browser tab icon. */
export function BrandMark({ className = '', label }: { className?: string; label?: string }) {
  return <img className={`brand-mark ${className}`} src={mark} alt={label ?? ''} width={40} height={48} draggable={false} />;
}

export default function Brand({ className = '' }: { className?: string }) {
  return <span className={`brand ${className}`}>
    <BrandMark />
    <span className="brand-wordmark">红海 <span className="brand-wordmark-latin">DataAgent</span></span>
  </span>;
}
