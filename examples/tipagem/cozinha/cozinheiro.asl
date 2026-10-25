// este agente sobrescreve o tipo de temperatura declarado no cozinha.mas2j
{ type(temperatura(atom, string)) }

temperatura(forno, "alta").

!cozinhar(bolo).

// erro 3: T é string (pela sobrescrita), mas é comparado com um número
+!cozinhar(X)
   : temperatura(forno, T) & T > 180
  <- servir(X);
     .stopMAS.
