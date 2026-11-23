import { createRouter, createWebHistory } from 'vue-router'
import { getToken, setUnauthorizedHandler } from '@/api/http'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue') },
    { path: '/', name: 'main', component: () => import('@/views/MainView.vue'), meta: { auth: true } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach((to) => {
  if (to.meta.auth && !getToken()) return { name: 'login' }
})

setUnauthorizedHandler(() => {
  if (router.currentRoute.value.name !== 'login') router.replace({ name: 'login' })
})
