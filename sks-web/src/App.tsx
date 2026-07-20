import { BrowserRouter, Routes, Route, Link } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const queryClient = new QueryClient();

function HomePage() {
  return (
    <main className="flex min-h-full flex-col items-center justify-center gap-2">
      <h1 className="text-2xl font-semibold">随口说</h1>
      <p className="text-paper-muted">P0 骨架占位页</p>
      <Link to="/about" className="text-paper-accent underline">
        关于
      </Link>
    </main>
  );
}

function AboutPage() {
  return (
    <main className="flex min-h-full flex-col items-center justify-center gap-2">
      <h1 className="text-2xl font-semibold">关于</h1>
      <Link to="/" className="text-paper-accent underline">
        返回首页
      </Link>
    </main>
  );
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/about" element={<AboutPage />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
