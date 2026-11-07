// erro 1: "duzentos" não é number (temperatura é declarada no cozinha.mas2j)
temperatura(forno, "duzentos").

!verificar.

// erro 2: T é number (vem de temperatura/2), mas ligar espera atom
+!verificar
   : temperatura(forno, T)
  <- !ligar(T).

+!ligar(Aparelho) <- .print("ligando ", Aparelho).
