mochila(amarela).
mochila(azul).
mochila(branca).
mochila(verde).
mochila(vermelha).

!levar(vermelho).                                  // ERRO: vermelho não é uma cor (é vermelha)

+!levar(C) : mochila(C) <- .print("levando a mochila ", C).
+!levar(C)              <- .print("não tenho mochila ", C, ", vou sem").

+!trocar : mochila(C) & C == azull <- .print("trocando para ", C).   // ERRO: azull (digitação)
