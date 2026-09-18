import { useEffect, useState } from 'react';
import { api } from './services/api';
import { AppLayout } from './components/layout/Layout';
import { useToast } from './components/common/Ui';
import { Auth } from './pages/Auth';
import { ShopperPage } from './pages/Shopper';
import { ManagerPage } from './pages/Manager';

export default function App() {
  const [user, setUser] = useState(() => {
    try { return JSON.parse(localStorage.getItem('user') || 'null'); } catch { return null; }
  });
  const [page, setPage] = useState(() => location.hash.slice(1) || 'dashboard');
  const [cartCount, setCartCount] = useState(0);
  const [toast, Toasts] = useToast();

  useEffect(() => {
    const change = () => setPage(location.hash.slice(1) || 'dashboard');
    const invalidateSession = () => setUser(null);
    addEventListener('hashchange', change);
    addEventListener('smartmart:auth-invalid', invalidateSession);
    return () => {
      removeEventListener('hashchange', change);
      removeEventListener('smartmart:auth-invalid', invalidateSession);
    };
  }, []);

  const go = (next) => { location.hash = next; };
  const refreshCartCount = () => {
    if (user?.role === 'SHOPPER') {
      api.cart().then((cart) => setCartCount(cart.items?.reduce((n, item) => n + item.quantity, 0) || 0)).catch(() => {});
    }
  };
  useEffect(refreshCartCount, [user]);

  if (!user) return <Auth onAuth={setUser} />;

  const logout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('user');
    setUser(null);
    location.hash = '';
  };

  return <AppLayout user={user} page={page} go={go} onLogout={logout} cartCount={cartCount}>
    {user.role === 'MANAGER'
      ? <ManagerPage page={page} toast={toast} />
      : <ShopperPage page={page} user={user} toast={toast} go={go} onCartChange={refreshCartCount} />}
    {Toasts}
  </AppLayout>;
}
