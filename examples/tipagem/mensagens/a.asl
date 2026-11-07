// a sobrescreve o tipo de temperatura e manda para b um valor que b não espera
{ type(temperatura(atom, string)) }

destino(b).
leitura("alta").

!avisar.
+!avisar
   : destino(D) & leitura(V)
  <- .send(D, tell, temperatura(forno, 200));   // ok
     C = temperatura(forno, V);                 // conteúdo montado em execução
     .send(D, tell, C).                         // destinatário é variável
