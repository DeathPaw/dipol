import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App'
import { installModalBehaviour } from './modalGuard'

// Ставим до первого рендера: слушатели висят на document и от React не зависят.
installModalBehaviour()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
