import React from 'react';

/** Approved product glyphs: outline at rest, solid for selected navigation. */
export const PRODUCT_ICONS = {
  book: {
    outline: <><rect x="3.5" y="4" width="6" height="16" rx="1.5"/><path d="M6.5 7.5v5"/><rect x="13" y="4" width="6" height="16" rx="1.5" transform="rotate(-12 16 12)"/></>,
    solid: <><path fillRule="evenodd" d="M4.8 3.5h3.4A1.8 1.8 0 0 1 10 5.3v13.4a1.8 1.8 0 0 1-1.8 1.8H4.8A1.8 1.8 0 0 1 3 18.7V5.3a1.8 1.8 0 0 1 1.8-1.8Zm1 3.5v5.5h1.4V7Z"/><path fillRule="evenodd" d="M14.3 3.5h3.4a1.8 1.8 0 0 1 1.8 1.8v13.4a1.8 1.8 0 0 1-1.8 1.8h-3.4a1.8 1.8 0 0 1-1.8-1.8V5.3a1.8 1.8 0 0 1 1.8-1.8Zm1 3.5v5.5h1.4V7Z" transform="rotate(-12 16 12)"/></>,
  },
  model: {
    outline: <><rect x="3.5" y="3.5" width="6" height="6" rx="1.5"/><rect x="14.5" y="14.5" width="6" height="6" rx="1.5"/><path d="M6.5 9.5v6a2 2 0 0 0 2 2h6M9.5 6.5h6a2 2 0 0 1 2 2v2"/></>,
    solid: <><path d="M6.5 8v7.5a2 2 0 0 0 2 2H16M8 6.5h7.5a2 2 0 0 1 2 2v2" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"/><rect x="3" y="3" width="7" height="7" rx="1.8"/><rect x="14" y="14" width="7" height="7" rx="1.8"/></>,
  },
  terms: {
    outline: <><path d="M3.5 6h11M9 6v13M6.5 19h5M16.5 11h4M16.5 15h4M16.5 19h4"/></>,
    solid: <><path d="M3 5.5h12v2H10v12H8v-12H3Z"/><rect x="16.5" y="10" width="4.5" height="2" rx=".7"/><rect x="16.5" y="14" width="4.5" height="2" rx=".7"/><rect x="16.5" y="18" width="4.5" height="2" rx=".7"/></>,
  },
  calendar: {
    outline: <><rect x="3.5" y="5" width="17" height="15.5" rx="2.5"/><path d="M7.5 3.5v3M16.5 3.5v3M3.5 10h17M8 14h2M14 14h2M8 17.5h2"/></>,
    solid: <><path fillRule="evenodd" d="M6 4h12a3 3 0 0 1 3 3v11a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3Zm-.5 5.5h13v-2h-13Zm2 4h2v2h-2Zm7 0h2v2h-2Zm-7 4h2V19h-2Z"/><path d="M7.5 3v3M16.5 3v3" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"/></>,
  },
  moreHorizontal: {
    outline: <><circle cx="5" cy="12" r="1.35" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.35" fill="currentColor" stroke="none"/><circle cx="19" cy="12" r="1.35" fill="currentColor" stroke="none"/></>,
    solid: <><circle cx="5" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="19" cy="12" r="1.6"/></>,
  },
  database: {
    outline: <><ellipse cx="12" cy="5.5" rx="8" ry="3"/><path d="M4 5.5v12c0 4 16 4 16 0v-12M4 11.5c0 4 16 4 16 0"/></>,
    solid: <><path fillRule="evenodd" d="M12 2.5c5 0 9 1.5 9 3.5v12c0 2-4 3.5-9 3.5S3 20 3 18V6c0-2 4-3.5 9-3.5ZM5 6c0 1.1 3.2 2 7 2s7-.9 7-2-3.2-2-7-2-7 .9-7 2Zm0 5.2v1.6c4 2.3 10 2.3 14 0v-1.6c-4 2.3-10 2.3-14 0Z"/></>,
  },
  table: {
    outline: <><rect x="3.5" y="3.5" width="17" height="17" rx="2.5"/><path d="M3.5 9h17M9 9v11.5M9 14.5h11.5"/></>,
    solid: <><path fillRule="evenodd" d="M6 3h12a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3Zm-.5 3v2H19V6ZM6 10v8h2v-8Zm4 0v3h8v-3Zm0 5v3h8v-3Z"/></>,
  },
  compose: {
    outline: <><path d="M12 4.5H6a2.5 2.5 0 0 0-2.5 2.5v11A2.5 2.5 0 0 0 6 20.5h11a2.5 2.5 0 0 0 2.5-2.5v-6M11 10.5l6.5-6.5a1.8 1.8 0 0 1 2.5 2.5L13.5 13l-4 1.5Z"/></>,
    solid: <><path d="M11.5 3H6a3 3 0 0 0-3 3v12a3 3 0 0 0 3 3h12a3 3 0 0 0 3-3v-5.5h-2V18a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h5.5Z"/><path d="m10.5 10.5 7-7a2.1 2.1 0 0 1 3 3l-7 7-4.5 1.5Z"/></>,
  },
};
