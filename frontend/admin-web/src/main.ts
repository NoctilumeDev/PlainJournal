import { createApp } from "vue";

import "@plain-journal/design-system/tokens.css";
import "@plain-journal/design-system/base.css";
import "@plain-journal/ui/styles.css";
import "./styles/admin.css";

import App from "./App.vue";
import { router } from "./router";
import { pinia } from "./stores/pinia";

createApp(App)
  .use(pinia)
  .use(router)
  .mount("#app");
