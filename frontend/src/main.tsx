import '@fontsource-variable/inter';
import '@fontsource-variable/space-grotesk';
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import './index.css'
import App from './App.tsx'

// Server-state cache (design decision 10, adopted page by page): data fetched with useQuery is
// shown instantly from cache on revisit and refreshed in the background once it is 30s old,
// instead of every page mount refetching and blanking the screen behind a spinner.
const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, refetchOnWindowFocus: false, retry: 1 } },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>,
)
