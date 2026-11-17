import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { AuthProvider } from './auth/AuthContext';
import { ReauthProvider } from './auth/ReauthProvider';
import { CartProvider } from './cart/CartContext';
import './styles.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <ReauthProvider>
          <CartProvider>
            <App />
          </CartProvider>
        </ReauthProvider>
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
);
