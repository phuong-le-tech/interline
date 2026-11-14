import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

// Intentionally empty M0 skeleton. Recording behavior is specified by AC14.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <main>
      <h1>Interline</h1>
      <p>Desktop scaffold</p>
    </main>
  </StrictMode>,
);
