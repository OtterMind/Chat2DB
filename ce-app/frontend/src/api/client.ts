import axios from 'axios'
import { backendOrigin } from './runtime'

const api = axios.create({
  baseURL: `${backendOrigin}/api`,
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
})

export default api
