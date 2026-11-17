import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthContext';
import { NavBar } from './components/NavBar';
import { AdminRoute, ProtectedRoute } from './components/routes';
import AccountPage from './pages/AccountPage';
import AdminPage from './pages/AdminPage';
import ConfirmEmailPage from './pages/ConfirmEmailPage';
import ForgotPasswordPage from './pages/ForgotPasswordPage';
import LoginPage from './pages/LoginPage';
import ResetPasswordPage from './pages/ResetPasswordPage';
import SignupPage from './pages/SignupPage';
import VerifyEmailPage from './pages/VerifyEmailPage';
import { AccountAlerts } from './components/AccountAlerts';
import BackofficeOrdersPage from './pages/backoffice/OrdersPage';
import ProductsPage from './pages/backoffice/ProductsPage';
import StockPage from './pages/backoffice/StockPage';
import CartPage from './pages/shop/CartPage';
import CatalogPage from './pages/shop/CatalogPage';
import CheckoutPage from './pages/shop/CheckoutPage';
import OrderDetailPage from './pages/shop/OrderDetailPage';
import OrdersPage from './pages/shop/OrdersPage';
import ProductPage from './pages/shop/ProductPage';

export default function App() {
  const { loading } = useAuth();

  if (loading) {
    return <div className="boot-splash">Loading…</div>;
  }

  return (
    <div className="app-shell">
      <NavBar />
      <main id="main-content" className="app-main" tabIndex={-1}>
        <AccountAlerts />
        <Routes>
          <Route path="/" element={<CatalogPage />} />
          <Route path="/products/:sku" element={<ProductPage />} />
          <Route path="/cart" element={<CartPage />} />
          <Route path="/checkout" element={<ProtectedRoute><CheckoutPage /></ProtectedRoute>} />
          <Route path="/orders" element={<ProtectedRoute><OrdersPage /></ProtectedRoute>} />
          <Route path="/orders/:id" element={<ProtectedRoute><OrderDetailPage /></ProtectedRoute>} />
          <Route path="/backoffice" element={<Navigate to="/backoffice/products" replace />} />
          <Route path="/backoffice/products" element={<AdminRoute><ProductsPage /></AdminRoute>} />
          <Route path="/backoffice/stock" element={<AdminRoute><StockPage /></AdminRoute>} />
          <Route path="/backoffice/orders" element={<AdminRoute><BackofficeOrdersPage /></AdminRoute>} />
          <Route path="/backoffice/orders/:id" element={<AdminRoute><BackofficeOrdersPage /></AdminRoute>} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignupPage />} />
          <Route path="/verify-email" element={<VerifyEmailPage />} />
          <Route path="/forgot-password" element={<ForgotPasswordPage />} />
          <Route path="/reset-password" element={<ResetPasswordPage />} />
          <Route path="/confirm-email" element={<ConfirmEmailPage />} />
          <Route path="/account" element={<ProtectedRoute><AccountPage /></ProtectedRoute>} />
          <Route path="/profile" element={<Navigate to="/account" replace />} />
          <Route path="/admin" element={<AdminRoute><AdminPage /></AdminRoute>} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  );
}
